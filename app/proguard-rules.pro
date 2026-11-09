# 插件单独编译，运行时才和这些库链接，R8 看不到它们的调用方。
# 保住公开 API 的名字和签名，内部实现照常优化。
#
# 和 PluginClassLoader.kt 的 SHARED_PACKAGES、Gradle 插件的排除列表保持同步。
-keep public class io.github.bytejuno.thetacore.sdk.** { public protected *; }
-keep public class kotlin.** { public protected *; }
-keep public class kotlinx.coroutines.** { public protected *; }
-keep public class kotlinx.serialization.** { public protected *; }
-keep public class androidx.compose.** { public protected *; }
-keep public class androidx.collection.** { public protected *; }
-keep public class androidx.lifecycle.** { public protected *; }
-keep public class androidx.savedstate.** { public protected *; }

# Explicit trusted in-process runtime contract. No UI/model consumer exists yet.
# Keep only the wire boundary; its implementation and ledger remain optimizable/obfuscatable.
-keepclassmembers,allowoptimization class io.github.bytejuno.thetacore.ThetaApplication {
    public java.lang.Object hostToolRequest(java.lang.String,kotlin.coroutines.Continuation);
    public java.lang.String hostToolRequestForInstrumentation(java.lang.String);
}

# networknt 1.5.3 uses direct ValidatorFactory lambdas, not reflective keyword constructors.
# Its draft/**, ucd/** and jsv-messages*.properties Java resources must remain packaged.
# Jackson discovers creators/annotations and generic types (including tree node factories).
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod
-keepclassmembers class com.fasterxml.jackson.databind.** {
    public <init>(...);
}
-keepclassmembers enum com.fasterxml.jackson.** { public static **[] values(); public static ** valueOf(java.lang.String); }
# Optional desktop-Java annotation support is guarded by Jackson's Java7Support loader.
# Android has no java.beans; tree parsing does not use these annotations.
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient

# Modern libxposed module entry; not the legacy constructor/annotation API.
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
