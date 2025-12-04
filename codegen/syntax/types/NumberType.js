export class NumberType {
    get canBePassedByReference() {
        // It's a primitive
        return false;
    }
    get kind() {
        return 'number';
    }
    getCode(language) {
        switch (language) {
            case 'c++':
                return 'double';
            case 'swift':
                return 'Double';
            case 'kotlin':
                return 'Double';
            default:
                throw new Error(`Language ${language} is not yet supported for NumberType!`);
        }
    }
    getExtraFiles() {
        return [];
    }
    getRequiredImports() {
        return [];
    }
}
