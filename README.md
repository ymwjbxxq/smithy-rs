# Crucible

Crucible is a tool to record and replay AWS SDK interactions.

## Usage

Recording:
```bash
mitmdump -s ./record.py
# In another tab / thread:
export HTTP_PROXY=http://localhost:8080
export HTTPS_PROXY=http://localhost:8080
curl http://crucible/record/start/<test-id>

# run your real AWS interaction, eg:
aws dynamodb list-tables

# Finalize the test:
curl http://crucible/record/stop
```

Test / Replay:
```bash
mitmdump -s ./test.py

# In another tab / thread:
export HTTP_PROXY=http://localhost:8080
export HTTPS_PROXY=http://localhost:8080
curl http://crucible/start_test/<test-id>
# run your faked test, eg:
aws dynamodb list-tables

# Finalize / check the test results:

curl http://crucible/check_test
```
