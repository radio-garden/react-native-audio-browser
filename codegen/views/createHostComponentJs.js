import { getHybridObjectName } from '../syntax/getHybridObjectName.js';
import { indent } from '../utils.js';
export function createHostComponentJs(spec) {
    const { T } = getHybridObjectName(spec.name);
    const props = spec.properties.map((p) => `"${p.name}": true`);
    props.push(`"hybridRef": true`);
    const code = `
{
  "uiViewClassName": "${T}",
  "supportsRawText": false,
  "bubblingEventTypes": {},
  "directEventTypes": {},
  "validAttributes": {
    ${indent(props.join(',\n'), '    ')}
  }
}
  `.trim();
    return [
        {
            content: code,
            language: 'json',
            name: `${T}Config.json`,
            platform: 'shared',
            subdirectory: [],
        },
    ];
}
