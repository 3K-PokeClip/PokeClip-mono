import { useOnboardingStore } from './onboarding';

const STORAGE_KEY = 'pc-onboarding';

function resetStore() {
  useOnboardingStore.setState({
    welcomeSeen: false,
    tourDone: false,
    channelLinked: false,
    pluginLinked: false,
    hydrated: false,
    tourStep: null,
  });
}

describe('useOnboardingStore', () => {
  beforeEach(() => {
    window.localStorage.clear();
    resetStore();
  });

  it('startTour는 웰컴을 소비하고 0단계에서 시작한다', () => {
    useOnboardingStore.getState().startTour();

    const s = useOnboardingStore.getState();
    expect(s.welcomeSeen).toBe(true);
    expect(s.tourStep).toBe(0);
  });

  it('skipTour·completeTour는 투어를 닫고 tourDone을 세운다', () => {
    useOnboardingStore.getState().startTour();
    useOnboardingStore.getState().skipTour();

    expect(useOnboardingStore.getState().tourStep).toBeNull();
    expect(useOnboardingStore.getState().tourDone).toBe(true);
  });

  it('플래그 변이는 localStorage에 기록된다', () => {
    useOnboardingStore.getState().setChannelLinked(true);
    useOnboardingStore.getState().markPluginLinked();

    const raw = window.localStorage.getItem(STORAGE_KEY);
    expect(raw).not.toBeNull();
    expect(JSON.parse(raw!)).toMatchObject({ channelLinked: true, pluginLinked: true });
  });

  it('hydrate는 저장된 플래그를 복원하되 tourStep은 건드리지 않는다', () => {
    window.localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ v: 1, welcomeSeen: true, channelLinked: true }),
    );
    useOnboardingStore.setState({ tourStep: 2 });

    useOnboardingStore.getState().hydrate();

    const s = useOnboardingStore.getState();
    expect(s.hydrated).toBe(true);
    expect(s.welcomeSeen).toBe(true);
    expect(s.channelLinked).toBe(true);
    expect(s.pluginLinked).toBe(false);
    expect(s.tourStep).toBe(2);
  });

  it('hydrate는 두 번 불러도 한 번만 읽는다 (StrictMode 이중 이펙트)', () => {
    useOnboardingStore.getState().hydrate();
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ v: 1, tourDone: true }));

    useOnboardingStore.getState().hydrate();

    expect(useOnboardingStore.getState().tourDone).toBe(false);
  });

  it('손상된 JSON은 무시하고 기본값으로 hydrate를 마친다', () => {
    window.localStorage.setItem(STORAGE_KEY, '{broken');

    useOnboardingStore.getState().hydrate();

    const s = useOnboardingStore.getState();
    expect(s.hydrated).toBe(true);
    expect(s.welcomeSeen).toBe(false);
  });

  it('boolean이 아닌 저장값은 필드 단위로 걸러낸다', () => {
    window.localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ v: 1, welcomeSeen: 'yes', tourDone: true }),
    );

    useOnboardingStore.getState().hydrate();

    expect(useOnboardingStore.getState().welcomeSeen).toBe(false);
    expect(useOnboardingStore.getState().tourDone).toBe(true);
  });
});
