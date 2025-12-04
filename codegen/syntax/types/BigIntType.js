export class BigIntType {
    get canBePassedByReference() {
        // It's a primitive.
        return false;
    }
    get kind() {
        return 'bigint';
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
                throw new Error(`Language ${language} is not yet supported for BigIntType!`);
        }
    }
    getExtraFiles() {
        return [];
    }
    getRequiredImports() {
        return [];
    }
}
