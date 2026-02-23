import dynamic from 'next/dynamic'

const TrackPlayerApp = dynamic(() => import('../components/TrackPlayerApp'), {
  ssr: false
})

export default function App() {
  return <TrackPlayerApp />
}
