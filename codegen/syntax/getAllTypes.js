// TODO: Structs or other HybridObjects may reference other types recursively - we need to add a `referencedTypes` prop to each `Type` to be able to resolve that.
export function getAllTypes(spec) {
    const types = [];
    // 1. Properties
    types.push(...spec.properties.map((p) => p.type));
    // 2. Method return types
    types.push(...spec.methods.map((m) => m.returnType));
    // 3. Method parameters
    types.push(...spec.methods.flatMap((m) => m.parameters.map((p) => p.type)));
    return types;
}
