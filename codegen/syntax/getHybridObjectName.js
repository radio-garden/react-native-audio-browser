export function getHybridObjectName(hybridObjectName) {
    return {
        T: hybridObjectName,
        HybridT: `Hybrid${hybridObjectName}`,
        HybridTSpec: `Hybrid${hybridObjectName}Spec`,
        HybridTSpecCxx: `Hybrid${hybridObjectName}Spec_cxx`,
        JHybridTSpec: `JHybrid${hybridObjectName}Spec`,
        HybridTSpecSwift: `Hybrid${hybridObjectName}SpecSwift`,
    };
}
