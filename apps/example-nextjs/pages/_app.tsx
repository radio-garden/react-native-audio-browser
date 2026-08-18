import FontAwesome6Brands from '@react-native-vector-icons/fontawesome6/fonts/FontAwesome6_Brands.ttf'
import FontAwesome6Regular from '@react-native-vector-icons/fontawesome6/fonts/FontAwesome6_Regular.ttf'
import FontAwesome6Solid from '@react-native-vector-icons/fontawesome6/fonts/FontAwesome6_Solid.ttf'
import Head from 'next/head'

// react-native-vector-icons renders glyphs by setting fontFamily. On native the
// fonts are linked into the app bundle; on web nothing registers them, so every
// icon silently renders as tofu until these @font-face rules exist. The family
// names must match what the package sets (FontAwesome6Free-Solid, etc.).
const iconFontFaces = `
@font-face {
  font-family: 'FontAwesome6Free-Solid';
  src: url(${FontAwesome6Solid}) format('truetype');
  font-display: block;
}
@font-face {
  font-family: 'FontAwesome6Free-Regular';
  src: url(${FontAwesome6Regular}) format('truetype');
  font-display: block;
}
@font-face {
  font-family: 'FontAwesome6Brands-Regular';
  src: url(${FontAwesome6Brands}) format('truetype');
  font-display: block;
}
`

export default function App({ Component, pageProps }) {
  return (
    <>
      <Head>
        <title>RNAB - Next.js Example</title>
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <style dangerouslySetInnerHTML={{ __html: iconFontFaces }} />
      </Head>
      <Component {...pageProps} />
    </>
  )
}
