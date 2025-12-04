export function groupByPlatform(files) {
    const result = { shared: [], ios: [], android: [] };
    for (const file of files) {
        result[file.platform].push(file);
    }
    return result;
}
