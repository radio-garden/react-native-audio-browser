import { escapeCppName } from '../helpers.js';
export class NamedWrappingType {
    type;
    name;
    constructor(name, type) {
        this.name = name;
        this.type = type;
    }
    get escapedName() {
        return escapeCppName(this.name);
    }
    get kind() {
        return this.type.kind;
    }
    get isEquatable() {
        return this.type.isEquatable;
    }
    get canBePassedByReference() {
        return this.type.canBePassedByReference;
    }
    getCode(language, options) {
        return this.type.getCode(language, options);
    }
    getExtraFiles() {
        return this.type.getExtraFiles();
    }
    getRequiredImports(language) {
        return this.type.getRequiredImports(language);
    }
}
