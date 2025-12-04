import {} from '../SourceFile.js';
import { ErrorType } from './ErrorType.js';
import { FunctionType } from './FunctionType.js';
import { NamedWrappingType } from './NamedWrappingType.js';
import { VoidType } from './VoidType.js';
export class PromiseType {
    resultingType;
    errorType;
    constructor(resultingType) {
        this.resultingType = resultingType;
        this.errorType = new ErrorType();
    }
    get canBePassedByReference() {
        // It's a future<..>, it cannot be copied.
        return true;
    }
    get kind() {
        return 'promise';
    }
    get resolverFunction() {
        if (this.resultingType.kind === 'void') {
            return new FunctionType(new VoidType(), []);
        }
        else {
            return new FunctionType(new VoidType(), [
                new NamedWrappingType('value', this.resultingType),
            ]);
        }
    }
    get rejecterFunction() {
        return new FunctionType(new VoidType(), [
            new NamedWrappingType('error', this.errorType),
        ]);
    }
    getCode(language, options) {
        const resultingCode = this.resultingType.getCode(language, options);
        switch (language) {
            case 'c++':
                return `std::shared_ptr<Promise<${resultingCode}>>`;
            case 'swift':
                return `Promise<${resultingCode}>`;
            case 'kotlin':
                return `Promise<${resultingCode}>`;
            default:
                throw new Error(`Language ${language} is not yet supported for PromiseType!`);
        }
    }
    getExtraFiles(visited) {
        return this.resultingType.getExtraFiles(visited);
    }
    getRequiredImports(language, visited) {
        const imports = this.resultingType.getRequiredImports(language, visited);
        switch (language) {
            case 'c++':
                imports.push({
                    language: 'c++',
                    name: 'NitroModules/Promise.hpp',
                    space: 'system',
                });
                break;
            case 'swift':
                imports.push({
                    name: 'NitroModules',
                    language: 'swift',
                    space: 'system',
                });
                break;
            case 'kotlin':
                imports.push({
                    name: 'com.margelo.nitro.core.Promise',
                    language: 'kotlin',
                    space: 'system',
                });
                break;
        }
        return imports;
    }
}
