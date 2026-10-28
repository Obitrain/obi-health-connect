# Agent notes

## Android: Kotlin HybridObjects

Annotate every Kotlin HybridObject class and its constructor:

```kotlin
@Keep
@DoNotStrip
class HybridFoo @DoNotStrip constructor() : HybridFooSpec()
```

Nitro constructs hybrid objects from C++ via JNI, which R8 can't see. Without these annotations, R8 treats the class as never instantiated and nulls its constructor-set fields in minified apps (obiapp OBIAPP-8M / OBIAPP-8D). See [Nitro docs § 5.2 ProGuard](https://nitro.margelo.com/docs/concepts/nitrogen#52-optional-proguard).
