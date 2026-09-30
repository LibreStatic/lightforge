# ML Kit discovers these manifest registrars reflectively. Keep both their names and no-arg
# constructors; otherwise optimized benchmark/release builds start but fail when creating a
# bundled label or OCR client.
-keep class com.google.mlkit.**.*Registrar {
    public <init>();
}

# LiteRT 2.2 JNI looks up these concrete types, enum constants and constructors by name.
# The AAR's UsedByReflection rules omit TensorType's nested Kotlin classes; R8 otherwise
# removes ElementType and nativeGetInputTensorType aborts the entire process.
-keep class com.google.ai.edge.litert.TensorType { *; }
-keep class com.google.ai.edge.litert.TensorType$* { *; }
-keep class com.google.ai.edge.litert.TensorBufferRequirements { *; }
-keep class com.google.ai.edge.litert.LiteRtException { *; }
