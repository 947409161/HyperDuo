# The module entry point is named only from META-INF/xposed/java_init.list and is
# instantiated reflectively by the framework, so it must survive shrinking.
-keep class com.hyperduo.trio.HyperDuoModule { *; }

# The framework resolves hook targets and reads fields on the ROM's classes by
# name at runtime; nothing here is reachable from the entry point statically.
-keep class com.hyperduo.trio.** { *; }

# The libxposed API is compile-only and supplied by the framework at runtime.
-dontwarn io.github.libxposed.**
