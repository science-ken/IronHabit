# =============================================================================
# IronHabit — R8/ProGuard 保留规则
# release 开启 isMinifyEnabled + isShrinkResources，需要保留反射/kotlinx.serialization 相关成员
# =============================================================================

# ---- 通用属性保留（注解、签名、内部类）----
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepattributes RuntimeVisible*Annotations, AnnotationDefault

# =============================================================================
# kotlinx.serialization（编译期生成序列化器，需保留 Companion 与 serializer 方法）
# =============================================================================
-keepattributes RuntimeVisibleAnnotations
-dontnote kotlinx.serialization.**

# 保留所有 @Serializable 类的伴生对象与 serializer()
-keepclassmembers class **$$serializer {
    *** descriptor;
}
-keepclasseswithmembers class com.ironhabit.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.ironhabit.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.ironhabit.app.** {
    *** Companion;
}

# 领域模型/备份模型整体保留（反射与序列化命名依赖）
-keep class com.ironhabit.app.domain.model.** { *; }
-keep class com.ironhabit.app.data.local.dto.** { *; }

# =============================================================================
# Hilt / Dagger（生成代码依赖类名与注解）
# =============================================================================
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager { *; }
-dontwarn dagger.hilt.**

# 保留 @HiltViewModel 的构造器
-keepclasseswithmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}

# =============================================================================
# Room（DAO 为接口，实现由编译器生成）
# =============================================================================
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# =============================================================================
# 安全存储（androidx.security:security-crypto → com.google.crypto.tink）
# =============================================================================
# tink 的类上标注了 Google Error Prone 的注解（@CanIgnoreReturnValue /
# @CheckReturnValue / @Immutable / @RestrictedApi）。这些注解是 compile-only
# （仅编译期存在，运行时不加载），但 R8 在 release 压缩时会因「引用了找不到的类」
# 直接中止构建：
#   > Task :app:minifyReleaseWithR8 FAILED
#   ERROR: R8: Missing class com.google.errorprone.annotations.CanIgnoreReturnValue
#          (referenced from: com.google.crypto.tink...)
# debug 不开压缩（isMinifyEnabled=false）故不触发，只有 release 才会踩到。
# 注解缺失不影响运行逻辑，忽略该注解包即可。
-dontwarn com.google.errorprone.annotations.**

# =============================================================================
# Compose / Kotlin 元数据
# =============================================================================
-dontwarn org.jetbrains.annotations.**
-keep class kotlin.Metadata { *; }
