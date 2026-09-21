#!/bin/sh
# B2-CL06：6b607ec9 的记忆行结构（V101 代码真写）
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c \
  "select id, checkpoint_revision, schema_version, parent_memory_id is not null as has_parent, memory_digest from rca_working_memory where run_id='6b607ec9-4b8d-4062-95c0-cc7d307911cd' order by checkpoint_revision"
echo "== 父链自引用核对（parent 指向同 run 前一修订）=="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c \
  "select c.checkpoint_revision, p.checkpoint_revision as parent_rev from rca_working_memory c join rca_working_memory p on c.parent_memory_id=p.id where c.run_id='6b607ec9-4b8d-4062-95c0-cc7d307911cd' order by c.checkpoint_revision"
echo "== 末修订 memory_json 键结构（顶键+嵌套一层）=="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c \
  "select jsonb_object_keys(memory_json) from rca_working_memory where run_id='6b607ec9-4b8d-4062-95c0-cc7d307911cd' order by checkpoint_revision desc limit 1" 
echo "== 假设/反证相关键内容（末修订，截 600 字）=="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c \
  "select substring(memory_json::text, 1, 600) from rca_working_memory where run_id='6b607ec9-4b8d-4062-95c0-cc7d307911cd' order by checkpoint_revision desc limit 1"
