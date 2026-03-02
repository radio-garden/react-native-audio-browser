export class Int64Type {
    get canBePassedByReference() {
        // It's a primitive.
        return false;
    }
    get kind() {
        return 'int64';
    }
    get isEquatable() {
        return true;
    }
    getCode(language) {
        switch (language) {
            case 'c++':
                return 'int64_t';
            case 'swift':
                return 'Int64';
            case 'kotlin':
                return 'Long';
            default:
                throw new Error(`Language ${language} is not yet supported for Int64Type!`);
        }
    }
    getExtraFiles() {
        return [];
    }
    getRequiredImports() {
        return [];
    }
}
