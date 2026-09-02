package com.objwww.pr.broker.selfcheck;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallbackTemplate;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.objwww.pr.broker.service.SecurityProfileMapper;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.sandbox.JobSpec;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * M4-T00 Docker 可行性探针（方案 §2 任务表 M4-T00 行的 9 项清单逐项实证）。
 *
 * <p>在 195（有 dockerd）上直接运行：不依赖 Spring 容器，只用项目已引入的
 * docker-java 3.7.1（httpclient5 transport，显式 api.version=1.44）逐项探测，
 * 每项打印 {@code [T00-①..⑨] PASS/FAIL <名称> :: <关键证据值>}。
 * 任何一项 FAIL → 退出码 1；全绿 → 0。
 *
 * <p>配置（环境变量，均有默认值）：
 * <ul>
 *   <li>DOCKER_HOST：默认 unix:///var/run/docker.sock</li>
 *   <li>PROBE_API_VERSION：默认 1.44</li>
 *   <li>PROBE_IMAGE：Job 候选基础镜像，默认 eclipse-temurin:21-jre（INC-09 实测可跑的族）</li>
 *   <li>PROBE_BIND_SRC：② 只读 bind 的宿主源目录；默认现场创建临时目录
 *       （容器内运行探针时该路径按宿主命名空间解释，需显式指定）</li>
 * </ul>
 *
 * <p>⑥ KVM/Kata 是"可用性记录"项（M4-P2：T00 只记录不启用），记录成功即 PASS，
 * 可用与否体现在证据值里。
 */
public final class DockerFeasibilityProbe {

    private static final String LABEL_KEY = "pr.t00.probe";
    private static final long NANO_CPUS = 1_000_000_000L;
    private static final long MEMORY_BYTES = 256L * 1024 * 1024;
    private static final int PIDS_LIMIT = 256;

    private final DockerClient docker;
    private final String image;

    private record CheckResult(String item, String name, boolean pass, String evidence) {}

    private DockerFeasibilityProbe(DockerClient docker, String image) {
        this.docker = docker;
        this.image = image;
    }

    public static void main(String[] args) {
        String dockerHost = env("DOCKER_HOST", "unix:///var/run/docker.sock");
        String apiVersion = env("PROBE_API_VERSION", "1.44");
        String image = env("PROBE_IMAGE", "eclipse-temurin:21-jre");
        System.out.println("T00 探针启动：dockerHost=" + dockerHost + " apiVersion=" + apiVersion
                + " image=" + image);

        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .withApiVersion(apiVersion)
                .build();
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(10)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(120))
                .build();

        List<CheckResult> results = new ArrayList<>();
        try (DockerClient docker = DockerClientImpl.getInstance(config, httpClient)) {
            DockerFeasibilityProbe probe = new DockerFeasibilityProbe(docker, image);
            CheckResult first = probe.check01ConnectAndNegotiate(apiVersion);
            results.add(first);
            print(first);
            if (!first.pass()) {
                // dockerd 不可达时后续 8 项无探测意义，直接中止
                System.out.println("dockerd 不可达，后续 ②~⑨ 项中止");
                System.exit(1);
            }
            probe.ensureImage();
            for (CheckResult r : List.of(
                    probe.check02SecurityProfileFields(),
                    probe.check03ImageStartsUnderDefaultSeccomp(),
                    probe.check04AwaitCompletionTimeoutSemantics(),
                    probe.check05NetworkNoneEgressBlocked(),
                    probe.check06KvmKataAvailability(),
                    probe.check07SiblingContainerSpawn(),
                    probe.check08CgroupSwapAccounting(),
                    probe.check09ArchiveFromStoppedContainer())) {
                results.add(r);
                print(r);
            }
        } catch (Exception e) {
            System.out.println("[T00] FAIL 探针自身异常 :: " + e);
            results.add(new CheckResult("probe", "probe runtime", false, String.valueOf(e)));
        }

        long failed = results.stream().filter(r -> !r.pass()).count();
        System.out.println("T00 探针结果：" + (results.size() - failed) + "/" + results.size()
                + " PASS" + (failed > 0 ? "（" + failed + " 项 FAIL）" : ""));
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void print(CheckResult r) {
        System.out.println("[T00-" + r.item() + "] " + (r.pass() ? "PASS" : "FAIL") + " "
                + r.name() + " :: " + r.evidence());
    }

    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return v != null && !v.isBlank() ? v : defaultValue;
    }

    /** ① docker-java 3.7.1 + httpclient5 连接 dockerd（unix socket），显式 api 协商成功。 */
    private CheckResult check01ConnectAndNegotiate(String clientApiVersion) {
        try {
            docker.pingCmd().exec();
            Version version = docker.versionCmd().exec();
            Info info = docker.infoCmd().exec();
            String evidence = "ping=ok serverApi=" + version.getApiVersion()
                    + " serverVersion=" + version.getVersion()
                    + " clientApiVersion=" + clientApiVersion
                    + " kernel=" + info.getKernelVersion() + " driver=" + info.getDriver();
            return new CheckResult("①", "docker-java 连接与 API 协商", true, evidence);
        } catch (Exception e) {
            return new CheckResult("①", "docker-java 连接与 API 协商", false, String.valueOf(e));
        }
    }

    /**
     * ② §4.5 安全剖面全字段在 HostConfig/Config 类型安全表达（走 SecurityProfileMapper
     * 真实映射路径 + 附录 C 补全字段），create 后 docker inspect 逐项断言。
     */
    private CheckResult check02SecurityProfileFields() {
        String id = null;
        try {
            JobSpec spec = new JobSpec(UUID.randomUUID(), UUID.randomUUID(), 0L,
                    "REVIEW_TOOL_CALL",
                    new JobSpec.ImageRef(Digest.sha256Of("t00-image"), null),
                    List.of("/wrapper/run.sh"), Digest.sha256Of("t00-snapshot"), List.of(),
                    new JobSpec.ResourceLimits(NANO_CPUS, MEMORY_BYTES, MEMORY_BYTES, PIDS_LIMIT),
                    600, JobSpec.NetworkPolicy.NONE);
            HostConfig hostConfig = new SecurityProfileMapper().buildHostConfig(spec)
                    // 附录 C 补全：mapper 之外的 tmpfs/日志上限/只读 bind/privileged 否定
                    .withTmpFs(Map.of("/work", "rw,noexec,nosuid,size=64m",
                            "/out", "rw,noexec,nosuid,size=32m",
                            "/tmp", "rw,noexec,nosuid,size=64m"))
                    .withLogConfig(new LogConfig(LogConfig.LoggingType.JSON_FILE,
                            Map.of("max-size", "10m", "max-file", "3")))
                    .withBinds(new Bind(bindSourceDir(), new Volume("/src"), AccessMode.ro))
                    .withPrivileged(false);

            id = docker.createContainerCmd(image)
                    .withHostConfig(hostConfig)
                    .withName(probeName("profile"))
                    .withUser("65534")
                    .withEntrypoint("/bin/sh")
                    .withCmd("-c", "true")
                    .withEnv("PATH=/usr/local/bin:/usr/bin:/bin", "HOME=/work/home", "LANG=C.UTF-8")
                    .withLabels(Map.of(LABEL_KEY, "true", "pr.sandbox.managed", "true"))
                    .exec().getId();

            InspectContainerResponse inspect = docker.inspectContainerCmd(id).exec();
            HostConfig hc = inspect.getHostConfig();
            ContainerConfig cfg = inspect.getConfig();
            List<String> bad = new ArrayList<>();
            check(bad, "NetworkMode", "none", hc.getNetworkMode());
            check(bad, "ReadonlyRootfs", Boolean.TRUE, hc.getReadonlyRootfs());
            check(bad, "Privileged", Boolean.FALSE, hc.getPrivileged());
            check(bad, "NanoCPUs", NANO_CPUS, hc.getNanoCPUs());
            check(bad, "Memory", MEMORY_BYTES, hc.getMemory());
            check(bad, "MemorySwap", MEMORY_BYTES, hc.getMemorySwap());
            check(bad, "PidsLimit", (long) PIDS_LIMIT, hc.getPidsLimit());
            if (hc.getCapDrop() == null || !Arrays.asList(hc.getCapDrop()).contains(Capability.ALL)) {
                bad.add("CapDrop=" + Arrays.toString(hc.getCapDrop()));
            }
            if (hc.getSecurityOpts() == null
                    || !hc.getSecurityOpts().contains("no-new-privileges")) {
                bad.add("SecurityOpts=" + hc.getSecurityOpts());
            }
            if (hc.getTmpFs() == null
                    || !hc.getTmpFs().keySet().containsAll(List.of("/work", "/out", "/tmp"))) {
                bad.add("TmpFs=" + hc.getTmpFs());
            }
            if (hc.getLogConfig() == null || hc.getLogConfig().getType() == null
                    || !"json-file".equals(hc.getLogConfig().getType().getType())
                    || !"10m".equals(hc.getLogConfig().getConfig().get("max-size"))) {
                bad.add("LogConfig=" + hc.getLogConfig());
            }
            boolean srcRo = hc.getBinds() != null && Arrays.stream(hc.getBinds()).anyMatch(b ->
                    "/src".equals(b.getVolume().getPath()) && b.getAccessMode() == AccessMode.ro);
            if (!srcRo) {
                bad.add("Binds=" + Arrays.toString(hc.getBinds()));
            }
            check(bad, "Config.User", "65534", cfg.getUser());
            if (!Arrays.equals(cfg.getEntrypoint(), new String[]{"/bin/sh"})) {
                bad.add("Config.Entrypoint=" + Arrays.toString(cfg.getEntrypoint()));
            }
            if (cfg.getLabels() == null
                    || !"true".equals(cfg.getLabels().get("pr.sandbox.managed"))) {
                bad.add("Config.Labels=" + cfg.getLabels());
            }
            return bad.isEmpty()
                    ? new CheckResult("②", "安全剖面全字段 inspect 断言", true, "14 项字段断言全过")
                    : new CheckResult("②", "安全剖面全字段 inspect 断言", false, String.join("; ", bad));
        } catch (Exception e) {
            return new CheckResult("②", "安全剖面全字段 inspect 断言", false, String.valueOf(e));
        } finally {
            removeQuietly(id);
        }
    }

    /** ③ Job 基础镜像在默认 seccomp profile 下可启动 + 镜像 Entrypoint/Cmd/User/WorkingDir 留档。 */
    private CheckResult check03ImageStartsUnderDefaultSeccomp() {
        String id = null;
        try {
            InspectImageResponse imageInfo = docker.inspectImageCmd(image).exec();
            ContainerConfig imageConfig = imageInfo.getConfig();
            // 不设任何 seccomp SecurityOpt = 默认 profile（DP-32：显式不走 unconfined）
            id = docker.createContainerCmd(image)
                    .withHostConfig(HostConfig.newHostConfig().withNetworkMode("none"))
                    .withName(probeName("seccomp"))
                    .withCmd("java", "-version")
                    .withLabels(Map.of(LABEL_KEY, "true"))
                    .exec().getId();
            docker.startContainerCmd(id).exec();
            int exitCode = docker.waitContainerCmd(id).start()
                    .awaitStatusCode(60, TimeUnit.SECONDS);
            String evidence = "exitCode=" + exitCode
                    + " imageEntrypoint=" + Arrays.toString(imageConfig.getEntrypoint())
                    + " imageCmd=" + Arrays.toString(imageConfig.getCmd())
                    + " imageUser=" + imageConfig.getUser()
                    + " imageWorkingDir=" + imageConfig.getWorkingDir();
            return new CheckResult("③", "默认 seccomp 下镜像启动 + inspect 留档",
                    exitCode == 0, evidence);
        } catch (Exception e) {
            return new CheckResult("③", "默认 seccomp 下镜像启动 + inspect 留档", false,
                    String.valueOf(e));
        } finally {
            removeQuietly(id);
        }
    }

    /** ④ awaitCompletion(timeout) 超时返回 false 且 close() 中止在途 HTTP（F-18/F-35）。 */
    private CheckResult check04AwaitCompletionTimeoutSemantics() {
        String id = null;
        try {
            id = docker.createContainerCmd(image)
                    .withName(probeName("await"))
                    .withCmd("sh", "-c", "sleep 30")
                    .withLabels(Map.of(LABEL_KEY, "true"))
                    .exec().getId();
            docker.startContainerCmd(id).exec();

            WaitContainerResultCallback callback = docker.waitContainerCmd(id).start();
            callback.awaitStarted(10, TimeUnit.SECONDS);
            boolean completed = callback.awaitCompletion(2, TimeUnit.SECONDS);
            long closeStart = System.nanoTime();
            callback.close();
            long closeMillis = (System.nanoTime() - closeStart) / 1_000_000;

            boolean stillRunning = Boolean.TRUE.equals(
                    docker.inspectContainerCmd(id).exec().getState().getRunning());
            boolean pass = !completed && closeMillis < 5_000 && stillRunning;
            String evidence = "awaitCompletion(2s)=" + completed
                    + " close耗时=" + closeMillis + "ms close后容器仍在运行=" + stillRunning;
            return new CheckResult("④", "awaitCompletion 超时/close 中止语义", pass, evidence);
        } catch (Exception e) {
            return new CheckResult("④", "awaitCompletion 超时/close 中止语义", false,
                    String.valueOf(e));
        } finally {
            removeQuietly(id);
        }
    }

    /** ⑤ --network none 下出网探测行为实测（预期：连接失败，EGRESS_BLOCKED）。 */
    private CheckResult check05NetworkNoneEgressBlocked() {
        String id = null;
        try {
            id = docker.createContainerCmd(image)
                    .withHostConfig(HostConfig.newHostConfig().withNetworkMode("none"))
                    .withName(probeName("egress"))
                    .withCmd("bash", "-c",
                            "(exec 3<>/dev/tcp/1.1.1.1/80) 2>/dev/null "
                                    + "&& echo EGRESS_OPEN || echo EGRESS_BLOCKED")
                    .withLabels(Map.of(LABEL_KEY, "true"))
                    .exec().getId();
            docker.startContainerCmd(id).exec();
            docker.waitContainerCmd(id).start().awaitStatusCode(60, TimeUnit.SECONDS);
            String output = collectLogs(id);
            boolean blocked = output.contains("EGRESS_BLOCKED") && !output.contains("EGRESS_OPEN");
            return new CheckResult("⑤", "network none 出网探测", blocked,
                    "容器输出=" + output.trim());
        } catch (Exception e) {
            return new CheckResult("⑤", "network none 出网探测", false, String.valueOf(e));
        } finally {
            removeQuietly(id);
        }
    }

    /** ⑥ ls /dev/kvm + Kata 可用性记录（F-33/M4-P2：T00 只记录不启用）。 */
    private CheckResult check06KvmKataAvailability() {
        try {
            Path kvm = Path.of("/dev/kvm");
            boolean kvmExists = Files.exists(kvm);
            String kata;
            Process proc = new ProcessBuilder("kata-runtime", "check")
                    .redirectErrorStream(true).start();
            boolean done = proc.waitFor(15, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                kata = "kata-runtime check 超时";
            } else {
                String out = new String(proc.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8).trim();
                kata = "kata-runtime check rc=" + proc.exitValue() + " 输出首行="
                        + out.lines().findFirst().orElse("<empty>");
            }
            String evidence = "/dev/kvm 存在=" + kvmExists + "；"
                    + (kvmExists ? "可读=" + Files.isReadable(kvm) + " 可写=" + Files.isWritable(kvm)
                            + "；" : "")
                    + kata + "（注意：容器内运行探针时反映的是容器 /dev 视图）";
            return new CheckResult("⑥", "KVM/Kata 可用性记录", true, evidence);
        } catch (IOException e) {
            // kata-runtime 不在 PATH 属正常记录结果（云上虚拟机常关嵌套虚拟化，M4-P2）
            return new CheckResult("⑥", "KVM/Kata 可用性记录", true,
                    "/dev/kvm 存在=" + Files.exists(Path.of("/dev/kvm"))
                            + "；kata-runtime 不在 PATH（" + e.getMessage() + "）");
        } catch (Exception e) {
            return new CheckResult("⑥", "KVM/Kata 可用性记录", false, String.valueOf(e));
        }
    }

    /** ⑦ 同机 docker.sock spawn 兄弟容器验证（探针自身经 sock 创建/启动/回收容器即实证）。 */
    private CheckResult check07SiblingContainerSpawn() {
        String id = null;
        try {
            boolean inContainer = Files.exists(Path.of("/.dockerenv"));
            id = docker.createContainerCmd(image)
                    .withHostConfig(HostConfig.newHostConfig().withNetworkMode("none"))
                    .withName(probeName("sibling"))
                    .withCmd("sh", "-c", "echo sibling-ok")
                    .withLabels(Map.of(LABEL_KEY, "true", "pr.t00.probe.sibling", "true"))
                    .exec().getId();
            docker.startContainerCmd(id).exec();
            int exitCode = docker.waitContainerCmd(id).start()
                    .awaitStatusCode(60, TimeUnit.SECONDS);
            String output = collectLogs(id);
            boolean pass = exitCode == 0 && output.contains("sibling-ok");
            String evidence = "探针运行上下文=" + (inContainer ? "容器内（挂 sock）" : "宿主机直连")
                    + " 兄弟容器=" + id.substring(0, 12) + " exitCode=" + exitCode
                    + " 输出=" + output.trim();
            return new CheckResult("⑦", "docker.sock spawn 兄弟容器", pass, evidence);
        } catch (Exception e) {
            return new CheckResult("⑦", "docker.sock spawn 兄弟容器", false, String.valueOf(e));
        } finally {
            removeQuietly(id);
        }
    }

    /**
     * ⑧ cgroup swap accounting 确认（MemorySwap=Memory 生效前提，F-37②）：
     * dockerd 侧以 info.swapLimit 为准；宿主侧记录 cgroup 版本与 memsw 文件。
     */
    private CheckResult check08CgroupSwapAccounting() {
        try {
            Info info = docker.infoCmd().exec();
            Boolean swapLimit = info.getSwapLimit();
            boolean cgroupV2 = Files.exists(Path.of("/sys/fs/cgroup/cgroup.controllers"));
            boolean memswV1 = Files.exists(Path.of("/sys/fs/cgroup/memory/memory.memsw.limit_inbytes"));
            String evidence = "dockerd swapLimit=" + swapLimit
                    + " cgroupDriver=" + info.getCGroupDriver()
                    + " kernel=" + info.getKernelVersion()
                    + " cgroupV2=" + cgroupV2 + " cgroupV1 memsw 文件存在=" + memswV1
                    + "（容器内运行探针时 /sys 反映容器视图，以 swapLimit 为准）";
            return new CheckResult("⑧", "cgroup swap accounting 确认",
                    Boolean.TRUE.equals(swapLimit), evidence);
        } catch (Exception e) {
            return new CheckResult("⑧", "cgroup swap accounting 确认", false, String.valueOf(e));
        }
    }

    /** ⑨ archive API（GET /containers/{id}/archive）对已停止容器流式提取 /out。 */
    private CheckResult check09ArchiveFromStoppedContainer() {
        String id = null;
        try {
            id = docker.createContainerCmd(image)
                    .withHostConfig(HostConfig.newHostConfig().withNetworkMode("none"))
                    .withName(probeName("archive"))
                    .withCmd("sh", "-c", "mkdir -p /out && echo t00-probe > /out/result.txt")
                    .withLabels(Map.of(LABEL_KEY, "true"))
                    .exec().getId();
            docker.startContainerCmd(id).exec();
            int exitCode = docker.waitContainerCmd(id).start()
                    .awaitStatusCode(60, TimeUnit.SECONDS);
            boolean running = Boolean.TRUE.equals(
                    docker.inspectContainerCmd(id).exec().getState().getRunning());

            List<String> entries = new ArrayList<>();
            String content = null;
            try (InputStream tar = docker.copyArchiveFromContainerCmd(id, "/out").exec();
                    TarArchiveInputStream in = new TarArchiveInputStream(tar)) {
                TarArchiveEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    entries.add(entry.getName());
                    if (entry.isFile() && entry.getName().endsWith("result.txt")) {
                        content = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                    }
                }
            }
            boolean pass = exitCode == 0 && !running && "t00-probe".equals(content);
            String evidence = "exitCode=" + exitCode + " 已停止=" + !running
                    + " tar条目=" + entries + " result.txt 内容=" + content;
            return new CheckResult("⑨", "已停止容器 archive 流式提取 /out", pass, evidence);
        } catch (Exception e) {
            return new CheckResult("⑨", "已停止容器 archive 流式提取 /out", false, String.valueOf(e));
        } finally {
            removeQuietly(id);
        }
    }

    // ------------------------------------------------------------------ 内部助手

    private static void check(List<String> bad, String field, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            bad.add(field + " 期望=" + expected + " 实际=" + actual);
        }
    }

    /** 镜像不在本地则拉取（T00 一次性前置；运行期禁 pull 是 Job 剖面纪律，与探针无关）。 */
    private void ensureImage() throws InterruptedException {
        try {
            docker.inspectImageCmd(image).exec();
            System.out.println("镜像已在本地：" + image);
        } catch (NotFoundException e) {
            System.out.println("镜像不在本地，拉取：" + image);
            String repo = image;
            String tag = "latest";
            int colon = image.lastIndexOf(':');
            if (colon > 0 && image.indexOf('/') < colon) {
                repo = image.substring(0, colon);
                tag = image.substring(colon + 1);
            }
            boolean done = docker.pullImageCmd(repo).withTag(tag).start()
                    .awaitCompletion(600, TimeUnit.SECONDS);
            if (!done) {
                throw new IllegalStateException("镜像拉取超时：" + image);
            }
        }
    }

    private String collectLogs(String containerId) throws InterruptedException, IOException {
        StringBuilder sb = new StringBuilder();
        ResultCallbackTemplate<?, Frame> callback = new LogContainerResultCallback() {
            @Override
            public void onNext(Frame frame) {
                sb.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
            }
        };
        try (callback) {
            docker.logContainerCmd(containerId).withStdOut(true).withStdErr(true).exec(callback);
            callback.awaitCompletion(30, TimeUnit.SECONDS);
        }
        return sb.toString();
    }

    private String bindSourceDir() throws IOException {
        String configured = System.getenv("PROBE_BIND_SRC");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return Files.createTempDirectory("t00-probe-src").toString();
    }

    private String probeName(String purpose) {
        return "t00-probe-" + purpose + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void removeQuietly(String containerId) {
        if (containerId == null) {
            return;
        }
        try {
            docker.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
        } catch (Exception e) {
            System.out.println("清理容器失败（需手工 docker rm -f）：" + containerId + " :: " + e);
        }
    }
}
