import {} from '../SourceFile.js';
export class ArrayType {
    itemType;
    constructor(itemType) {
        this.itemType = itemType;
    }
    get canBePassedByReference() {
        // It's a vector<..>, heavy to copy
        return true;
    }
    get kind() {
        return 'array';
    }
    get isEquatable() {
        return this.itemType.isEquatable;
    }
    getCode(language, options) {
        const itemCode = this.itemType.getCode(language, options);
        switch (language) {
            case 'c++':
                return `std::vector<${itemCode}>`;
            case 'swift':
                return `[${itemCode}]`;
            case 'kotlin':
                switch (this.itemType.kind) {
                    case 'number':
                        return 'DoubleArray';
                    case 'boolean':
                        return 'BooleanArray';
                    case 'int64':
                        return 'LongArray';
                    case 'uint64':
                        // ULong is just interpret as LongArray in Java.
                        return 'LongArray';
                    default:
                        return `Array<${itemCode}>`;
                }
            default:
                throw new Error(`Language ${language} is not yet supported for ArrayType!`);
        }
    }
    getExtraFiles() {
        return this.itemType.getExtraFiles();
    }
    getRequiredImports(language) {
        const imports = [
            ...this.itemType.getRequiredImports(language),
        ];
        if (language === 'c++') {
            imports.push({
                name: 'vector',
                language: 'c++',
                space: 'system',
            });
        }
        return imports;
    }
}
