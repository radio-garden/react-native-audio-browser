import { View } from 'react-native';

const DEFAULT_SPACE = 20;

export type SpacerMode = 'horizontal' | 'vertical' | 'expand';

const SPACER_STYLES = {
  horizontal: (size: number) => ({ width: size }),
  vertical: (size: number) => ({ height: size }),
  expand: () => ({ flex: 1 }),
};

export function Spacer({
  mode = 'vertical',
  size = DEFAULT_SPACE,
}: {
  mode?: SpacerMode;
  size?: number;
}) {
  const style = SPACER_STYLES[mode](size);
  return <View style={style} />;
}
