import {} from '../SourceFile.js';
import { ErrorType } from './ErrorType.js';
export class ResultWrappingType {
    result;
    error;
    constructor(result) {
        this.result = result;
        this.error = new ErrorType();
    }
    get canBePassedByReference() {
        return this.result.canBePassedByReference;
    }
    get kind() {
        return 'result-wrapper';
    }
    get isEquatable() {
        return this.result.isEquatable && this.error.isEquatable;
    }
    getCode(language, options) {
        const type = this.result.getCode(language, options);
        switch (language) {
            case 'c++':
                return `Result<${type}>`;
            case 'swift':
                return type;
            default:
                throw new Error(`Language ${language} is not yet supported for VariantType!`);
        }
    }
    getExtraFiles() {
        return [...this.result.getExtraFiles(), ...this.error.getExtraFiles()];
    }
    getRequiredImports(language) {
        const imports = [
            ...this.result.getRequiredImports(language),
            ...this.error.getRequiredImports(language),
        ];
        if (language === 'c++') {
            imports.push({
                language: 'c++',
                name: 'NitroModules/Result.hpp',
                space: 'system',
            });
        }
        return imports;
    }
}
