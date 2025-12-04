import { createNamedType } from './createType.js';
export function getInterfaceProperties(language, interfaceType) {
    const symbol = interfaceType.getAliasSymbol() ?? interfaceType.getSymbol();
    if (symbol == null)
        throw new Error(`Interface "${interfaceType.getText()}" does not have a Symbol!`);
    return interfaceType.getProperties().map((prop) => {
        let propType = prop.getDeclaredType();
        if (propType.isAny() || propType.isUnknown()) {
            // the interface is aliased/merged - we need to look into the actual declaration
            for (const declaration of symbol.getDeclarations()) {
                const declared = prop.getTypeAtLocation(declaration);
                if (!declared.isAny() && !declared.isUnknown()) {
                    propType = declared;
                    break;
                }
            }
        }
        const refType = createNamedType(language, prop.getName(), propType, prop.isOptional() || propType.isNullable());
        return refType;
    });
}
