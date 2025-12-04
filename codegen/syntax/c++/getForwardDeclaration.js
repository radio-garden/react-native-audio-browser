export function getForwardDeclaration(kind, className, namespace) {
    if (namespace != null) {
        return `
// Forward declaration of \`${className}\` to properly resolve imports.
namespace ${namespace} { ${kind} ${className}; }
  `.trim();
    }
    else {
        return `
// Forward declaration of \`${className}\` to properly resolve imports.
${kind} ${className};
    `.trim();
    }
}
