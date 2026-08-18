# ML Kit discovers these manifest registrars reflectively. Keep both their names and no-arg
# constructors; otherwise optimized benchmark/release builds start but fail when creating a
# bundled label or OCR client.
-keep class com.google.mlkit.**.*Registrar {
    public <init>();
}
