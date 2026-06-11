## Summary

- 

## Safety Checklist

- [ ] I did not commit generated certificates, private keys, databases, logs, APKs or storage roots.
- [ ] I considered upload, restore, delete, sync and interrupted-transfer behavior.
- [ ] I updated documentation or tests where behavior changed.

## Testing

- [ ] `cd shadow_daemon && go test ./...`
- [ ] `cd shadow_daemon && go vet ./...`
- [ ] `cd shadow_client && ./gradlew assembleDebug`
- [ ] Real-device test, if file behavior changed

## Notes

