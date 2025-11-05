import Icon from '@react-native-vector-icons/fontawesome6';
import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Dimensions,
  Linking,
  Modal,
  Platform,
  Pressable,
  StatusBar,
  StyleSheet,
  TouchableOpacity,
  View,
} from 'react-native';
import { useActiveTrack } from 'react-native-audio-browser';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { configurePlayer } from 'src/services/configure';
import {
  ActionSheet,
  Button,
  OptionSheet,
  PlayerControls,
  Progress,
  Spacer,
  TrackInfo,
} from './src/components';
import { useSetupPlayer } from './src/services/usePlayer';

configurePlayer();

export default function App() {
  const isPlayerReady = useSetupPlayer();
  return (
    <SafeAreaProvider>
      {isPlayerReady ? (
        <Player />
      ) : (
        <SafeAreaView style={styles.screenContainer}>
          <ActivityIndicator />
        </SafeAreaView>
      )}
    </SafeAreaProvider>
  );
}

function Player() {
  const track = useActiveTrack();
  const [optionsVisible, setOptionsVisible] = useState(false);
  const [actionsVisible, setActionsVisible] = useState(false);

  useEffect(() => {
    // This event will be fired when the app is already open and the notification is clicked
    const subscription = Linking.addEventListener('url', data => {
      console.log('deepLinkHandler', data.url);
    });

    // When you launch the closed app from the notification or any other link
    Linking.getInitialURL()
      .then(url => console.log('getInitialURL', url))
      .catch(console.error);

    return () => {
      subscription.remove();
    };
  }, []);

  return (
    <SafeAreaView style={styles.screenContainer}>
      <StatusBar barStyle={'light-content'} />
      <View style={styles.contentContainer}>
        <View style={styles.topBarContainer}>
          <Button
            title="Options"
            onPress={() => setOptionsVisible(true)}
            type="primary"
          />
          <Button
            title="Actions"
            onPress={() => setActionsVisible(true)}
            type="primary"
          />
        </View>
        <TrackInfo track={track} />
        <Progress live={track?.isLiveStream} />
        <Spacer />
        <PlayerControls />
        <Spacer mode={'expand'} />
      </View>
      <Modal
        visible={optionsVisible}
        animationType="fade"
        transparent={true}
        onRequestClose={() => setOptionsVisible(false)}
      >
        <TouchableOpacity
          style={styles.modalOverlay}
          activeOpacity={1}
          onPress={() => setOptionsVisible(false)}
        >
          <View
            style={styles.modalContent}
            onStartShouldSetResponder={() => true}
            onTouchEnd={e => e.stopPropagation()}
          >
            <View style={styles.modalHeader}>
              <Pressable
                onPress={() => setOptionsVisible(false)}
                style={styles.closeButton}
              >
                <Icon name="xmark" size={24} color="white" iconStyle="solid" />
              </Pressable>
            </View>
            <OptionSheet />
          </View>
        </TouchableOpacity>
      </Modal>
      <Modal
        visible={actionsVisible}
        animationType="fade"
        transparent={true}
        onRequestClose={() => setActionsVisible(false)}
      >
        <TouchableOpacity
          style={styles.modalOverlay}
          activeOpacity={1}
          onPress={() => setActionsVisible(false)}
        >
          <View
            style={styles.modalContent}
            onStartShouldSetResponder={() => true}
            onTouchEnd={e => e.stopPropagation()}
          >
            <View style={styles.modalHeader}>
              <Pressable
                onPress={() => setActionsVisible(false)}
                style={styles.closeButton}
              >
                <Icon name="xmark" size={24} color="white" iconStyle="solid" />
              </Pressable>
            </View>
            <ActionSheet />
          </View>
        </TouchableOpacity>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screenContainer: {
    flex: 1,
    backgroundColor: '#212121',
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: Platform.OS === 'web' ? Dimensions.get('window').height : '100%',
  },
  contentContainer: {
    flex: 1,
    alignItems: 'center',
  },
  topBarContainer: {
    width: '100%',
    flexDirection: 'row',
    justifyContent: 'flex-end',
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
    justifyContent: 'flex-end',
  },
  modalContent: {
    backgroundColor: '#181818',
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    maxHeight: '50%',
  },
  modalHeader: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    paddingHorizontal: 16,
    paddingTop: 12,
    paddingBottom: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#333',
  },
  closeButton: {
    padding: 8,
  },
});
