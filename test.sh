#!/bin/bash
curl http://crucible/start_test/list-tables
aws dynamodb list-tables --exclusive-start-table-name hello
curl http://crucible/check_test
