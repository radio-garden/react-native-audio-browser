export class UInt64Type {
    get canBePassedByReference() {
        // It's a primitive.
        return false;
    }
    get kind() {
        return 'uint64';
    }
    get isEquatable() {
        return true;
    }
    getCode(language) {
        switch (language) {
            case 'c++':
                return 'uint64_t';
            case 'swift':
                return 'UInt64';
            case 'kotlin':
                return 'ULong';
            default:
                throw new Error(`Language ${language} is not yet supported for UInt64Type!`);
        }
    }
    getExtraFiles() {
        return [];
    }
    getRequiredImports() {
        return [];
    }
}
