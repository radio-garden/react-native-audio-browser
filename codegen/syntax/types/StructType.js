import { NitroConfig } from '../../config/NitroConfig.js';
import { createCppStruct } from '../c++/CppStruct.js';
import { getForwardDeclaration } from '../c++/getForwardDeclaration.js';
import {} from '../SourceFile.js';
// Module-level re-entrancy guards for cyclic struct types.
// Node.js is single-threaded so these are safe.
const isEquatableVisited = new Set();
const extraFilesVisited = new Set();
const requiredImportsVisited = new Set();
export class StructType {
    structName;
    _propertiesInput;
    _properties = null;
    _declarationFile = null;
    _isInitializing = false;
    constructor(structName, properties) {
        this.structName = structName;
        this._propertiesInput = properties;
        if (this.structName.startsWith('__')) {
            throw new Error(`Struct name cannot start with two underscores (__) as this is reserved syntax for Nitrogen! (In ${this.structName})`);
        }
        // If properties were provided directly (not lazy), initialize eagerly.
        if (Array.isArray(properties)) {
            this._properties = properties;
            this._declarationFile = createCppStruct(structName, properties);
            if (this._properties.length === 0) {
                throw new Error(`Empty structs are not supported in Nitrogen! Add at least one property to ${this.structName}.`);
            }
        }
    }
    ensureInitialized() {
        if (this._properties !== null)
            return;
        if (this._isInitializing)
            return; // Re-entrancy guard for cyclic types
        this._isInitializing = true;
        try {
            const props = typeof this._propertiesInput === 'function'
                ? this._propertiesInput()
                : this._propertiesInput;
            this._properties = props;
            this._declarationFile = createCppStruct(this.structName, props);
            if (this._properties.length === 0) {
                throw new Error(`Empty structs are not supported in Nitrogen! Add at least one property to ${this.structName}.`);
            }
        }
        finally {
            this._isInitializing = false;
        }
    }
    get initialized() {
        return this._declarationFile !== null;
    }
    get properties() {
        this.ensureInitialized();
        return this._properties;
    }
    get declarationFile() {
        this.ensureInitialized();
        return this._declarationFile;
    }
    get canBePassedByReference() {
        // It's a struct, heavy to copy.
        return true;
    }
    get kind() {
        return 'struct';
    }
    get isEquatable() {
        // Re-entrancy guard: if we are already checking isEquatable for this
        // struct (cyclic reference), return true as an optimistic default.
        if (isEquatableVisited.has(this)) {
            return true;
        }
        isEquatableVisited.add(this);
        try {
            return this.properties.every((p) => p.isEquatable);
        }
        finally {
            isEquatableVisited.delete(this);
        }
    }
    getCode(language, { fullyQualified } = {}) {
        switch (language) {
            case 'c++':
                if (fullyQualified) {
                    return NitroConfig.current.getCxxNamespace('c++', this.structName);
                }
                else {
                    return this.structName;
                }
            case 'swift':
                return this.structName;
            case 'kotlin':
                return this.structName;
            default:
                throw new Error(`Language ${language} is not yet supported for StructType!`);
        }
    }
    getExtraFiles() {
        if (extraFilesVisited.has(this))
            return [];
        // If not yet initialized (partially constructed during a cycle), skip.
        if (!this.initialized)
            return [];
        extraFilesVisited.add(this);
        try {
            const referencedTypes = this.declarationFile.referencedTypes.flatMap((r) => r.getExtraFiles());
            return [this.declarationFile, ...referencedTypes];
        }
        finally {
            extraFilesVisited.delete(this);
        }
    }
    getRequiredImports(language) {
        if (requiredImportsVisited.has(this))
            return [];
        const imports = [];
        if (language === 'c++') {
            const cxxNamespace = NitroConfig.current.getCxxNamespace('c++');
            if (!this.initialized) {
                // During cyclic initialization, declarationFile isn't available yet.
                // Use the structName directly to construct the import.
                imports.push({
                    name: `${this.structName}.hpp`,
                    language: 'c++',
                    forwardDeclaration: getForwardDeclaration('struct', this.structName, cxxNamespace),
                    space: 'user',
                });
            }
            else {
                requiredImportsVisited.add(this);
                try {
                    imports.push({
                        name: this.declarationFile.name,
                        language: this.declarationFile.language,
                        forwardDeclaration: getForwardDeclaration('struct', this.structName, cxxNamespace),
                        space: 'user',
                    });
                }
                finally {
                    requiredImportsVisited.delete(this);
                }
            }
        }
        return imports;
    }
}
