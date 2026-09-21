#!/bin/sh
docker exec deploy-control-app-1 sh -c "unzip -l /app/app.jar | grep -iE 'catalog|postmortem|correlation' | head -6" 2>/dev/null || docker exec deploy-control-app-1 sh -c "cd /tmp && jar tf /app/app.jar | grep -iE 'catalog|postmortem' | head -6"
