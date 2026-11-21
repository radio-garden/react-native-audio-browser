import { NitroConfig } from '../../config/NitroConfig.js';
import { JAVA_ERROR_IMPORT } from '../c++/withoutJavaStack.js';
import { getHybridObjectName } from '../getHybridObjectName.js';
export function createJNIHybridObjectRegistration({ hybridObjectName, jniClassName, }) {
    const { JHybridTSpec } = getHybridObjectName(hybridObjectName);
    const jniNamespace = NitroConfig.current.getAndroidPackage('c++/jni', jniClassName);
    return {
        requiredImports: [
            { name: `${JHybridTSpec}.hpp`, language: 'c++', space: 'user' },
            {
                name: 'NitroModules/DefaultConstructableObject.hpp',
                language: 'c++',
                space: 'system',
            },
            // Not upstream: callJava.
            JAVA_ERROR_IMPORT,
        ],
        // Not upstream: callJava.
        cppDefinition: `
struct ${JHybridTSpec}Impl: public jni::JavaClass<${JHybridTSpec}Impl, ${JHybridTSpec}::JavaPart> {
  static constexpr auto kJavaDescriptor = "L${jniNamespace};";
  static std::shared_ptr<${JHybridTSpec}> create() {
    static const auto constructorFn = javaClassStatic()->getConstructor<${JHybridTSpec}Impl::javaobject()>();
    jni::local_ref<${JHybridTSpec}::JavaPart> javaPart = callJava([&] { return javaClassStatic()->newObject(constructorFn); });
    return javaPart->get${JHybridTSpec}();
  }
};
    `.trim(),
        cppCode: `
HybridObjectRegistry::registerHybridObjectConstructor(
  "${hybridObjectName}",
  []() -> std::shared_ptr<HybridObject> {
    return ${JHybridTSpec}Impl::create();
  }
);
      `.trim(),
    };
}
