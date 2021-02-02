#!/bin/bash
curl http://crucible/record/start/list-tables
aws dynamodb list-tables --exclusive-start-table-name hello
curl http://crucible/record/stop