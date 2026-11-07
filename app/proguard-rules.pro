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
