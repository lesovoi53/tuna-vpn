AndroidManifest.axml is the compiled public application manifest from the synthetic
debug build (processDebugResources). It contains no profiles or credentials.
UpdateSecurityTest packages it with a fake native library and generates ephemeral
test-only signing keys in JUnit temporary directories. These APKs are cryptographic
fixtures, not runnable app builds and must never be installed or distributed.
