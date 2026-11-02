// 업로드 창의 코드 조각. 열어야 쓰는 창이라 편집기 첫 로드에서 뺐다.
//
// 🔴 StudioScreen은 이 조각을 받은 뒤에만 창을 그린다. next/dynamic은 React.lazy라, 조각을 못 받은 채 그리면 렌더에서 오류를 던져
// 편집기가 통째로 오류 화면으로 넘어가고 고친 것을 잃는다.
export const loadUploadDialog = () => import('./UploadInfoDialog');
