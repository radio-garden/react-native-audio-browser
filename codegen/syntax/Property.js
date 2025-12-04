import { capitalizeName } from '../utils.js';
import {} from './SourceFile.js';
import { Method } from './Method.js';
import { VoidType } from './types/VoidType.js';
import { Parameter } from './Parameter.js';
import { isBooleanPropertyPrefix } from './helpers.js';
export class Property {
    name;
    type;
    isReadonly;
    constructor(name, type, isReadonly) {
        this.name = name;
        this.type = type;
        this.isReadonly = isReadonly;
        if (this.name.startsWith('__')) {
            throw new Error(`Property names are not allowed to start with two underscores (__)! (In ${this.jsSignature})`);
        }
    }
    get jsSignature() {
        return `${this.name}: ${this.type.kind}`;
    }
    getExtraFiles(visited) {
        return this.type.getExtraFiles(visited);
    }
    getRequiredImports(language, visited) {
        return this.type.getRequiredImports(language, visited);
    }
    getGetterName(environment) {
        if (this.type.kind === 'boolean' && isBooleanPropertyPrefix(this.name)) {
            // Boolean accessors where the property starts with "is" or "has" are renamed in JVM and Swift
            switch (environment) {
                case 'jvm':
                case 'swift':
                    // isSomething -> isSomething()
                    return this.name;
                default:
                    break;
            }
        }
        // isSomething -> getIsSomething()
        return `get${capitalizeName(this.name)}`;
    }
    getSetterName(environment) {
        if (this.type.kind === 'boolean' && this.name.startsWith('is')) {
            // Boolean accessors where the property starts with "is" are renamed in JVM
            if (environment === 'jvm') {
                // isSomething -> setSomething()
                const cleanName = this.name.replace('is', '');
                return `set${capitalizeName(cleanName)}`;
            }
        }
        // isSomething -> setIsSomething()
        return `set${capitalizeName(this.name)}`;
    }
    get cppGetter() {
        return new Method(this.getGetterName('other'), this.type, []);
    }
    get cppSetter() {
        if (this.isReadonly)
            return undefined;
        const parameter = new Parameter(this.name, this.type);
        return new Method(this.getSetterName('other'), new VoidType(), [parameter]);
    }
    getCppMethods() {
        if (this.cppSetter != null) {
            // get + set
            return [this.cppGetter, this.cppSetter];
        }
        else {
            // get
            return [this.cppGetter];
        }
    }
    getCode(language, modifiers, body) {
        if (body != null) {
            body.getter = body.getter.trim();
            body.setter = body.setter.trim();
        }
        switch (language) {
            case 'c++': {
                const methods = this.getCppMethods();
                const [getter, setter] = methods;
                const lines = [];
                lines.push(getter.getCode('c++', modifiers, body?.getter));
                if (setter != null) {
                    lines.push(setter.getCode('c++', modifiers, body?.setter));
                }
                return lines.join('\n');
            }
            case 'swift': {
                const type = this.type.getCode('swift');
                let accessors;
                if (body == null) {
                    accessors = this.isReadonly ? `get` : `get set`;
                }
                else {
                    const lines = [];
                    lines.push(`
get {
  ${body.getter}
}
          `);
                    if (!this.isReadonly) {
                        lines.push(`
set {
  ${body.setter}
}
            `);
                    }
                    accessors = '\n' + lines.join('\n') + '\n';
                }
                return `var ${this.name}: ${type} { ${accessors} }`;
            }
            case 'kotlin': {
                const type = this.type.getCode('kotlin');
                let keyword = this.isReadonly ? 'val' : 'var';
                if (modifiers?.virtual)
                    keyword = `abstract ${keyword}`;
                const lines = [];
                if (modifiers?.doNotStrip) {
                    lines.push('@get:DoNotStrip', '@get:Keep');
                    if (!this.isReadonly) {
                        lines.push('@set:DoNotStrip', '@set:Keep');
                    }
                }
                lines.push(`${keyword} ${this.name}: ${type}`);
                if (body != null) {
                    lines.push(`
  get() {
    ${body.getter}
  }
          `);
                    if (!this.isReadonly) {
                        lines.push(`
  set(value) {
    ${body.setter}
  }
            `);
                    }
                }
                return lines.join('\n');
            }
            default:
                throw new Error(`Language ${language} is not yet supported for properties!`);
        }
    }
}
