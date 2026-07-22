# Shadow Runtime Inputs

This directory contains the Shadow runtime pieces packaged with the plugin bundle.

```text
loader/sample-loader-debug.apk
runtime/sample-runtime-debug.apk
compile-only/shadow-runtime.jar
```

`shadow-runtime.jar` is compile-only input for `plugin-app`. The final `.shadowpkg` contains the
loader APK, runtime APK, business plugin APK, `config.json`, `termux-shadow.json`, and
`checksums.sha256`.
