#!/bin/sh
grep -c 'run-tag' /tmp/std.env || echo 'NO-RUN-TAG'
grep -o '"run-tag":"[^"]*"' /tmp/std.env || true
