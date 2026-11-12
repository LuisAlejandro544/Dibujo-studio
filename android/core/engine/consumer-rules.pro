# Conserva los nombres de clase JNI para que R8/ProGuard en builds release (Uptodown) no rompa los símbolos de Rust.
-keep class com.trazostudio.engine.NativeEngineBridge { *; }
