import {} from '../SourceFile.js';
export class TupleType {
    itemTypes;
    constructor(itemTypes) {
        this.itemTypes = itemTypes;
    }
    get canBePassedByReference() {
        // It's a tuple<..> - heavy to copy
        return true;
    }
    get kind() {
        return 'tuple';
    }
    get isEquatable() {
        return this.itemTypes.every((t) => t.isEquatable);
    }
    getCode(language, options) {
        const types = this.itemTypes.map((t) => t.getCode(language, options));
        switch (language) {
            case 'c++':
                return `std::tuple<${types.join(', ')}>`;
            case 'swift':
                throw new Error(`Tuple (${types.join(', ')}) is not yet supported in Swift due to a Swift bug! See https://github.com/swiftlang/swift/issues/75865`);
            default:
                throw new Error(`Language ${language} is not yet supported for TupleType!`);
        }
    }
    getExtraFiles() {
        return this.itemTypes.flatMap((t) => t.getExtraFiles());
    }
    getRequiredImports(language) {
        const imports = this.itemTypes.flatMap((t) => t.getRequiredImports(language));
        if (language === 'c++') {
            imports.push({
                language: 'c++',
                name: 'tuple',
                space: 'system',
            });
        }
        return imports;
    }
}
