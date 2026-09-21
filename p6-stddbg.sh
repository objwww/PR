#!/bin/sh
docker inspect eval-worker-std --format 'IMAGE={{.Config.Image}}'
docker inspect eval-worker-std --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -E '^SPRING_DATASOURCE|^SPRING_FLYWAY|^CHAOS|^JAVA_TOOL' | sed -E 's/(PASSWORD=....).*/\1**/'
echo '---SPRING_APPLICATION_JSON keys only---'
docker inspect eval-worker-std --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -E '^SPRING_APPLICATION_JSON=' | sed -E 's/(prompt-digest...)[a-f0-9]{16}/\1.../; s/(api-key..)[^,}]*/\1**/; s/(master-key..)[^,}]*/\1**/; s/(webhook-bearer..)[^,}]*/\1**/' | cut -c1-900
echo '---cmd args---'
docker inspect eval-worker-std --format '{{join .Config.Cmd " "}}' | cut -c1-500
echo '---mounts---'
docker inspect eval-worker-std --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{println}}{{end}}'
echo '---networks---'
docker inspect eval-worker-std --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}'
