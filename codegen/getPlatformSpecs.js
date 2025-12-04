import { Node, Symbol } from 'ts-morph';
import { getBaseTypes } from './utils.js';
const platformLanguages = {
    ios: ['swift', 'c++'],
    android: ['kotlin', 'c++'],
};
const allPlatforms = Object.keys(platformLanguages);
const allLanguages = Object.values(platformLanguages).flatMap((l) => l);
function isValidLanguage(language) {
    if (language == null) {
        return false;
    }
    return allLanguages.includes(language);
}
function isValidPlatform(platform) {
    return allPlatforms.includes(platform);
}
function getLiteralValue(symbol) {
    const value = symbol.getValueDeclaration();
    if (value == null) {
        return undefined;
    }
    const type = value.getType();
    const literal = type.getLiteralValue();
    if (typeof literal === 'string') {
        return literal;
    }
    return undefined;
}
// TODO: The type casting result here doesn't really work in TS.
function isValidLanguageForPlatform(language, platform) {
    return platformLanguages[platform].includes(language);
}
function getPlatformSpec(typeName, platformSpecs) {
    const result = {};
    // Properties (ios, android)
    const properties = platformSpecs.getProperties();
    for (const property of properties) {
        // Property name (ios, android)
        const platform = property.getName();
        if (!isValidPlatform(platform)) {
            throw new Error(`${typeName} does not properly extend HybridObject<T> - "${platform}" is not a valid Platform! ` +
                `Valid platforms are: [${allPlatforms.join(', ')}]`);
        }
        // Value (swift, kotlin, c++)
        const language = getLiteralValue(property);
        if (!isValidLanguage(language)) {
            throw new Error(`${typeName}: Language ${language} is not a valid language for ${platform}! ` +
                `Valid languages are: [${platformLanguages[platform].join(', ')}]`);
        }
        // Double-check that language works on this platform (android: kotlin/c++, ios: swift/c++)
        if (!isValidLanguageForPlatform(language, platform)) {
            throw new Error(`${typeName}: Language ${language} is not a valid language for ${platform}! ` +
                `Valid languages are: [${platformLanguages[platform].join(', ')}]`);
        }
        // @ts-expect-error because TypeScript isn't smart enough yet to correctly cast after the `isValidLanguageForPlatform` check.
        result[platform] = language;
    }
    return result;
}
function isDirectlyType(type, name) {
    const symbol = type.getSymbol() ?? type.getAliasSymbol();
    if (symbol?.getName() === name) {
        return true;
    }
    return false;
}
function extendsType(type, name, recursive) {
    for (const base of getBaseTypes(type)) {
        const isHybrid = isDirectlyType(base, name);
        if (isHybrid) {
            return true;
        }
        if (recursive) {
            const baseExtends = extendsType(base, name, recursive);
            if (baseExtends) {
                return true;
            }
        }
    }
    return false;
}
export function isDirectlyHybridObject(type) {
    return isDirectlyType(type, 'HybridObject');
}
export function isDirectlyAnyHybridObject(type) {
    return isDirectlyType(type, 'AnyHybridObject');
}
export function extendsHybridObject(type, recursive) {
    return extendsType(type, 'HybridObject', recursive);
}
export function isHybridViewProps(type) {
    return extendsType(type, 'HybridViewProps', true);
}
export function isHybridViewMethods(type) {
    return extendsType(type, 'HybridViewMethods', true);
}
export function isHybridView(type) {
    // HybridViews are type aliases for `HybridView`, and `Props & Methods` are just intersected together.
    const unionTypes = type.getIntersectionTypes();
    for (const union of unionTypes) {
        const symbol = union.getSymbol();
        if (symbol == null)
            return false;
        return symbol.getName() === 'HybridViewTag';
    }
    return false;
}
export function isAnyHybridSubclass(type) {
    if (isDirectlyHybridObject(type))
        return false;
    if (isHybridView(type))
        return true;
    if (extendsHybridObject(type, true))
        return true;
    return false;
}
/**
 * If the given interface ({@linkcode declaration}) extends `HybridObject`,
 * this method returns the platforms it exists on.
 * If it doesn't extend `HybridObject`, this returns `undefined`.
 */
export function getHybridObjectPlatforms(declaration) {
    const base = getBaseTypes(declaration.getType()).find((t) => isDirectlyHybridObject(t));
    if (base == null) {
        // this type does not extend `HybridObject`.
        throw new Error(`Couldn't find HybridObject<..> base for ${declaration.getName()}! (${declaration.getText()})`);
    }
    const genericArguments = base.getTypeArguments();
    const platformSpecsArgument = genericArguments[0];
    if (platformSpecsArgument == null ||
        platformSpecsArgument.getProperties().length === 0) {
        // it uses `HybridObject` without generic arguments. We throw as we don't know what to generate.
        throw new Error(`HybridObject ${declaration.getName()} does not declare any platforms in the \`HybridObject\` type argument! ` +
            `Pass at least one platform (and language) to \`interface ${declaration.getName()} extends HybridObject<{ ... }>\``);
    }
    return getPlatformSpec(declaration.getName(), platformSpecsArgument);
}
export function getHybridViewPlatforms(view) {
    if (Node.isTypeAliasDeclaration(view)) {
        const hybridViewTypeNode = view.getTypeNode();
        const isHybridViewType = Node.isTypeReference(hybridViewTypeNode) &&
            hybridViewTypeNode.getTypeName().getText() === 'HybridView';
        if (!isHybridViewType) {
            throw new Error(`${view.getName()} looks like a HybridView, but doesn't seem to alias HybridView<...>!`);
        }
        const genericArguments = hybridViewTypeNode.getTypeArguments();
        const platformSpecArg = genericArguments[2];
        if (platformSpecArg != null) {
            return getPlatformSpec(view.getName(), platformSpecArg.getType());
        }
    }
    // it uses `HybridObject` without generic arguments. This defaults to platform native languages
    return { ios: 'swift', android: 'kotlin' };
}
