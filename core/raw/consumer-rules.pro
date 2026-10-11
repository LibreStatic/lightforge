# liblightforge_raw builds the render result through JNI: FindClass + GetMethodID("<init>",
# "([III)V"). Nothing in Kotlin calls that constructor, so R8 otherwise renames the class or
# drops the constructor and nativeRender aborts the process with "mid == null".
-keep class com.librestatic.lightforge.core.raw.RawNativeImage {
    <init>(int[], int, int);
}
