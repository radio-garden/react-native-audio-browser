type DeclarationKind = 'class' | 'struct' | 'enum class';
export declare function getForwardDeclaration(kind: DeclarationKind, className: string, namespace?: string): string;
export {};
