import {} from '../SourceFile.js';
export class ErrorType {
    constructor() { }
    get canBePassedByReference() {
        // It's a exception<..>, pass by reference.
        return true;
    }
    get kind() {
        return 'error';
    }
    getCode(language) {
        switch (language) {
            case 'c++':
                return `std::exception_ptr`;
            case 'swift':
                return `Error`;
            case 'kotlin':
                return `Throwable`;
            default:
                throw new Error(`Language ${language} is not yet supported for ThrowableType!`);
        }
    }
    getExtraFiles() {
        return [];
    }
    getRequiredImports(language) {
        const imports = [];
        if (language === 'c++') {
            imports.push({
                language: 'c++',
                name: 'exception',
                space: 'system',
            });
        }
        return imports;
    }
}
