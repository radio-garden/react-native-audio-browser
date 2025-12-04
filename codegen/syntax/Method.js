import {} from './SourceFile.js';
import { Parameter } from './Parameter.js';
import { indent } from '../utils.js';
export class Method {
    name;
    returnType;
    parameters;
    constructor(name, returnType, parameters) {
        this.name = name;
        this.returnType = returnType;
        this.parameters = parameters;
        if (this.name.startsWith('__')) {
            throw new Error(`Method names are not allowed to start with two underscores (__)! (In ${this.jsSignature})`);
        }
        if (this.name === 'dispose') {
            // .dispose() does some special JSI magic that we loose if the user overrides it in his spec.
            throw new Error(`dispose() must not be overridden from TypeScript! You can override dispose() natively.`);
        }
    }
    get jsSignature() {
        const returnType = this.returnType.kind;
        const params = this.parameters.map((p) => `${p.name}: ${p.type.kind}`);
        return `${this.name}(${params.join(', ')}): ${returnType}`;
    }
    getCode(language, modifiers, body) {
        body = body?.trim();
        switch (language) {
            case 'c++': {
                const returnType = this.returnType.getCode('c++');
                const params = this.parameters.map((p) => p.getCode('c++'));
                // C++ modifiers start in the beginning
                const name = modifiers?.classDefinitionName
                    ? `${modifiers.classDefinitionName}::${this.name}`
                    : this.name;
                let signature = `${returnType} ${name}(${params.join(', ')})`;
                if (modifiers?.inline)
                    signature = `inline ${signature}`;
                if (modifiers?.virtual)
                    signature = `virtual ${signature}`;
                if (modifiers?.noexcept)
                    signature = `${signature} noexcept`;
                if (modifiers?.override)
                    signature = `${signature} override`;
                if (body == null) {
                    // It's a function declaration (no body)
                    if (modifiers?.virtual) {
                        // if it is a virtual function, we have no implementation (= 0)
                        signature = `${signature} = 0`;
                    }
                    return `${signature};`;
                }
                else {
                    return `
${signature} {
  ${indent(body, '  ')}
}`.trim();
                }
            }
            case 'swift': {
                const params = this.parameters.map((p) => p.getCode('swift'));
                const returnType = this.returnType.getCode('swift');
                let signature = `func ${this.name}(${params.join(', ')}) throws -> ${returnType}`;
                if (modifiers?.inline)
                    signature = `@inline(__always)\n${signature}`;
                if (body == null) {
                    return signature;
                }
                else {
                    return `
${signature} {
  ${indent(body, '  ')}
}`.trim();
                }
            }
            case 'kotlin': {
                const params = this.parameters.map((p) => p.getCode('kotlin'));
                const returnType = this.returnType.getCode('kotlin');
                let signature = `fun ${this.name}(${params.join(', ')}): ${returnType}`;
                if (modifiers?.inline)
                    signature = `inline ${signature}`;
                if (modifiers?.override)
                    signature = `override ${signature}`;
                if (modifiers?.virtual)
                    signature = `abstract ${signature}`;
                if (modifiers?.doNotStrip)
                    signature = `@DoNotStrip\n@Keep\n${signature}`;
                if (body == null) {
                    return signature;
                }
                else {
                    return `
${signature} {
  ${indent(body, '  ')}
}
          `.trim();
                }
            }
            default:
                throw new Error(`Language ${language} is not yet supported for property getters!`);
        }
    }
    getExtraFiles(visited) {
        const returnTypeExtraFiles = this.returnType.getExtraFiles(visited);
        const paramsExtraFiles = this.parameters.flatMap((p) => p.getExtraFiles(visited));
        return [...returnTypeExtraFiles, ...paramsExtraFiles];
    }
    getRequiredImports(language, visited) {
        const returnTypeFiles = this.returnType.getRequiredImports(language, visited);
        const paramsImports = this.parameters.flatMap((p) => p.getRequiredImports(language, visited));
        return [...returnTypeFiles, ...paramsImports];
    }
}
