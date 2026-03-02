interface NitrogenOptions {
    baseDirectory: string;
    outputDirectory: string;
}
interface NitrogenResult {
    generatedFiles: string[];
    targetSpecsCount: number;
    generatedSpecsCount: number;
}
export declare function runNitrogen({ baseDirectory, outputDirectory, }: NitrogenOptions): Promise<NitrogenResult>;
export {};
