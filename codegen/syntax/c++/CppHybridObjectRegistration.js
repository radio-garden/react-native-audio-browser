export function createCppHybridObjectRegistration({ hybridObjectName, cppClassName, }) {
    return {
        requiredImports: [
            { name: `${cppClassName}.hpp`, language: 'c++', space: 'user' },
        ],
        cppCode: `
HybridObjectRegistry::registerHybridObjectConstructor(
  "${hybridObjectName}",
  []() -> std::shared_ptr<HybridObject> {
    static_assert(std::is_default_constructible_v<${cppClassName}>,
                  "The HybridObject \\"${cppClassName}\\" is not default-constructible! "
                  "Create a public constructor that takes zero arguments to be able to autolink this HybridObject.");
    return std::make_shared<${cppClassName}>();
  }
);
      `.trim(),
    };
}
