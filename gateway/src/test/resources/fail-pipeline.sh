#!/usr/bin/env bash
# Test failing pipeline: exits non-zero without writing a terminal status,
# to exercise the executor reconcile safety net.
exit 1
