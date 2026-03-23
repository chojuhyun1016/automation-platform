"""
RIMAN 그룹웨어 부재 신청 Playwright 자동화.

DevTools 분석 확정 사항:
  - 로그인: body#login_body, 템플릿 기반 렌더링
    · ID:    input#empNo       (name="empNo")
    · PW:    input#empNoPassword (name="empNoPassword")
    · Login: button#btnLogin
  - 날짜: placeholder="yyyy-mm-dd" 커스텀 datepicker (jQuery UI)
  - 결재선 모달: 체크박스 클릭 → 하단 결재선 자동 추가 (드래그 불필요)
  - 결재 작성 팝업: gw.riman.com/ekp/view/app/eapp/document/eapDocPopup (별도 window)
  - absenceType: Slack 전달값 그대로 select option label로 사용
"""

import os
import time
import boto3
from datetime import datetime
from playwright.sync_api import (
    sync_playwright,
    Page,
    BrowserContext,
    TimeoutError as PWTimeout
)

# ─── 환경변수 ─────────────────────────────────────────────────────────────────
LOGIN_URL         = os.environ["GW_LOGIN_URL"]
BASE_URL          = os.environ["GW_BASE_URL"]
TIMEOUT_MS        = int(os.environ.get("GW_TIMEOUT_SEC", "120")) * 1000
SCREENSHOT_BUCKET = os.environ.get("SCREENSHOT_BUCKET", "")
SCREENSHOT_PREFIX = os.environ.get("SCREENSHOT_PREFIX", "groupware-screenshots/")


# =============================================================================
# 유틸
# =============================================================================

def _shot(page: Page, label: str) -> None:
    """실패/성공 시점 스크린샷을 S3에 저장한다. 버킷 미설정 시 생략."""
    if not SCREENSHOT_BUCKET:
        return
    try:
        ts  = datetime.now().strftime("%Y%m%d_%H%M%S")
        key = f"{SCREENSHOT_PREFIX}{label}_{ts}.png"
        boto3.client("s3").put_object(
            Bucket=SCREENSHOT_BUCKET,
            Key=key,
            Body=page.screenshot(),
            ContentType="image/png"
        )
        print(f"[shot] s3://{SCREENSHOT_BUCKET}/{key}")
    except Exception as e:
        print(f"[shot] 저장 실패 (무시): {e}")


def _fill_datepicker(page: Page, nth: int, date_str: str) -> None:
    """
    날짜 입력 필드에 값을 입력한다.

    방식: 입력 필드 클릭 → datepicker 달력이 열리면 해당 날짜 직접 클릭
    → 달력에서 날짜를 클릭해야 그룹웨어 내부 onSelect 이벤트가 확실히 트리거됨
    → Tab/Enter는 onSelect 없이 포커스만 이동해 내부 유효성 미통과
    """
    from datetime import datetime as _dt

    selector = '.ui-dialog input[placeholder="yyyy-mm-dd"]'
    inp = page.locator(selector).nth(nth)

    # 1단계: 입력 필드 클릭 → datepicker 달력 열기
    inp.click()
    time.sleep(0.5)

    # 2단계: 달력이 열렸으면 해당 날짜로 네비게이션 후 날짜 클릭
    try:
        if page.locator(".ui-datepicker:visible").count() > 0:
            # 목표 날짜 파싱
            target = _dt.strptime(date_str, "%Y-%m-%d")
            target_year  = target.year
            target_month = target.month  # 1~12
            target_day   = target.day

            # 최대 24번 네비게이션 (2년치)
            for _ in range(24):
                # 현재 달력의 연월 확인
                cal = page.locator(".ui-datepicker:visible")

                # 연도/월 텍스트: "2026년 3월" 형태
                header_text = cal.locator(".ui-datepicker-title").inner_text()
                # 파싱: "2026년 3월" → year=2026, month=3
                import re as _re
                m = _re.search(r"(\d{4}).*?(\d{1,2})", header_text)
                if not m:
                    break
                cur_year  = int(m.group(1))
                cur_month = int(m.group(2))

                if cur_year == target_year and cur_month == target_month:
                    break
                elif (cur_year, cur_month) < (target_year, target_month):
                    # 다음 달로
                    next_btn = cal.locator(".ui-datepicker-next")
                    if next_btn.count() > 0:
                        next_btn.click()
                        time.sleep(0.3)
                    else:
                        break
                else:
                    # 이전 달로
                    prev_btn = cal.locator(".ui-datepicker-prev")
                    if prev_btn.count() > 0:
                        prev_btn.click()
                        time.sleep(0.3)
                    else:
                        break

            # 날짜 셀 클릭 (data-date 또는 텍스트로 찾기)
            cal = page.locator(".ui-datepicker:visible")
            day_str = str(target_day)
            # td.ui-state-default 또는 a.ui-state-default 텍스트가 날짜
            day_link = cal.locator(f"a.ui-state-default:text-is('{day_str}')").first
            if day_link.count() == 0:
                day_link = cal.locator(f"td:not(.ui-state-disabled) a:text-is('{day_str}')").first
            day_link.click()
            time.sleep(0.5)
        else:
            # 달력이 안 열린 경우 → 직접 타이핑 + 사유 필드로 포커스 이동
            inp.press("Control+a")
            inp.type(date_str, delay=80)
            # 사유 필드로 포커스 이동 (Tab 대신 직접 클릭)
            reason_field = page.locator("#myAbsenceWrite_input_absnWhy")
            if reason_field.count() > 0:
                reason_field.click()
            time.sleep(0.3)
    except Exception as e:
        print(f"[datepicker] 달력 클릭 실패, 직접 타이핑으로 대체: {e}")
        inp.press("Control+a")
        inp.type(date_str, delay=80)
        reason_field = page.locator("#myAbsenceWrite_input_absnWhy")
        if reason_field.count() > 0:
            reason_field.click()
        time.sleep(0.3)

    # datepicker가 아직 열려있으면 닫기
    try:
        if page.locator(".ui-datepicker:visible").count() > 0:
            page.keyboard.press("Escape")
            time.sleep(0.2)
    except Exception:
        pass


def _wait_for_page_ready(page: Page, timeout: int = 30000) -> None:
    """로딩 화면 사라질 때까지 대기."""
    try:
        page.wait_for_selector(".loading:not(:visible), .ui-loader:not(:visible)", timeout=10000)
    except Exception:
        pass
    try:
        page.wait_for_load_state("networkidle", timeout=5000)
    except Exception:
        pass
    time.sleep(1.0)


def _dismiss_warning_modals(page: Page, max_attempts: int = 5) -> bool:
    """
    폼 요소(input/select)가 없는 순수 알림 모달만 처리.
    반환값: True = 닫기로 닫힘(저장 실패), False = 정상
    """
    dismissed_by_close = False
    for _ in range(max_attempts):
        # JS 문자열 연결 방식 사용 - 따옴표 충돌 없음
        result = page.evaluate(
            "(function() {"
            "  var dialogs = document.querySelectorAll('.ui-dialog');"
            "  for (var i = 0; i < dialogs.length; i++) {"
            "    var dlg = dialogs[i];"
            "    var st = window.getComputedStyle(dlg);"
            "    if (st.display === 'none' || st.visibility === 'hidden') continue;"
            "    var formEls = dlg.querySelectorAll("
            "      'input[type=\"text\"], input[type=\"radio\"], "
            "input[type=\"checkbox\"], select, textarea'"
            "    );"
            "    if (formEls.length > 0) continue;"
            "    var btns = dlg.querySelectorAll('button');"
            "    var txt = (dlg.innerText || '').trim().substring(0, 80);"
            "    for (var j = 0; j < btns.length; j++) {"
            "      var t = (btns[j].innerText || '').trim();"
            "      if (t === '확인') { btns[j].click(); return {dismissed:true,type:'ok',modalText:txt}; }"
            "    }"
            "    for (var k = 0; k < btns.length; k++) {"
            "      var t2 = (btns[k].innerText || '').trim();"
            "      if (t2 === '닫기') { btns[k].click(); return {dismissed:true,type:'close',modalText:txt}; }"
            "    }"
            "  }"
            "  return {dismissed:false};"
            "})()"
        )
        if result.get("dismissed"):
            mtype = result.get("type", "")
            mtext = result.get("modalText", "")
            print(f"[warn_modal] type={mtype}, text='{mtext[:60]}'")
            if mtype == "close":
                dismissed_by_close = True
            page.wait_for_timeout(400)
        else:
            break
    return dismissed_by_close


# =============================================================================
# 메인 자동화 흐름
# =============================================================================

def run_absence_registration(credentials: dict, params: dict) -> bool:
    """
    그룹웨어 부재 신청 전체 자동화.
    """
    with sync_playwright() as p:
        browser = p.chromium.launch(
            headless=True,
            args=[
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--disable-extensions"
            ]
        )
        ctx  = browser.new_context(
            viewport={"width": 1280, "height": 900},
            locale="ko-KR"
        )
        page = ctx.new_page()
        popup = None

        try:
            # ── STEP 1: 로그인 ────────────────────────────────────────────
            print("[1/14] 로그인 페이지 로드")
            page.goto(LOGIN_URL, wait_until="domcontentloaded", timeout=TIMEOUT_MS)
            page.wait_for_selector("body#login_body", timeout=TIMEOUT_MS)
            page.wait_for_selector("#empNo", timeout=TIMEOUT_MS)
            _shot(page, "01_login_page")

            print("[2/14] ID/PW 입력")
            page.fill("#empNo",         credentials["id"])
            page.fill("#empNoPassword", credentials["password"])
            _shot(page, "02_login_filled")

            print("[3/14] Login 버튼 클릭")
            page.click("#btnLogin")
            page.wait_for_url("**/ekp/main/**", timeout=TIMEOUT_MS)
            print("[3/14] 로그인 성공")
            # 로그인 후 페이지 완전 로딩 대기
            _wait_for_page_ready(page, TIMEOUT_MS)
            _shot(page, "03_dashboard")

            # ── STEP 2: 근태관리 메뉴 클릭 ───────────────────────────────
            print("[4/14] 근태관리 메뉴 클릭")
            page.wait_for_selector(
                "a:has-text('근태관리'), li.nt a:has-text('근태관리')",
                timeout=TIMEOUT_MS
            )
            page.locator(
                "a:has-text('근태관리'), "
                "li:has-text('근태관리') > a, "
                "li.nt a:has-text('근태관리')"
            ).first.click()
            time.sleep(3)

            # ── iframe 탐색 헬퍼 ──────────────────────────────────────────
            def find_frame_with(selector, timeout_sec=60):
                import time as _time
                deadline = _time.time() + timeout_sec
                while _time.time() < deadline:
                    for frame in page.frames:
                        try:
                            if frame.locator(selector).count() > 0:
                                return frame
                        except Exception:
                            continue
                    _time.sleep(1)
                return None

            # ── STEP 3: iframe에서 좌측 "근태 등록" 버튼 탐색 ───────────
            print("[5/14] iframe에서 근태 등록 버튼 탐색 중...")
            # 좌측 사이드바 "근태 등록" 버튼이 있는 프레임 찾기
            lnb_frame = find_frame_with(
                "button:has-text('근태 등록'), "
                ".lnb button:has-text('근태 등록'), "
                "#lnb button:has-text('근태 등록')",
                timeout_sec=60
            )
            if lnb_frame is None:
                # 대안: 근태 등록 텍스트가 있는 모든 프레임
                lnb_frame = find_frame_with("'근태 등록'", timeout_sec=10)
            if lnb_frame is None:
                raise Exception("근태 등록 버튼 프레임을 찾지 못했습니다.")

            print(f"[5/14] 프레임 발견: {lnb_frame.url}")
            _shot(page, "04_attendance_menu")

            # 좌측 "근태 등록" 버튼 클릭
            print("[5/14] 근태 등록 버튼 클릭")
            lnb_frame.locator(
                "button:has-text('근태 등록')"
            ).first.click()
            time.sleep(1.0)
            _shot(page, "04b_after_lnb_click")

            # ── STEP 4: 드롭다운에서 "부재 등록" 클릭 ────────────────────
            print("[5/14] 드롭다운 부재 등록 클릭")
            # 드롭다운 메뉴에서 "부재 등록" 항목 클릭
            lnb_frame.wait_for_selector(
                "li:has-text('부재 등록') a, "
                "a:has-text('부재 등록'), "
                "ul li a:has-text('부재 등록')",
                timeout=10000
            )
            lnb_frame.locator(
                "li:has-text('부재 등록') a, "
                "a:has-text('부재 등록'), "
                "ul li a:has-text('부재 등록')"
            ).first.click()
            time.sleep(1.5)

            # ── STEP 5: 부재 등록 모달 열림 대기 (메인 page 기준) ────────
            # 모달은 iframe 밖 메인 페이지에 렌더링됨
            print("[6/14] 부재 등록 모달 대기...")
            page.wait_for_selector(".ui-dialog:visible", timeout=TIMEOUT_MS)
            time.sleep(1.0)
            _shot(page, "05_absence_modal_opened")

            # ── STEP 6: 부재명 선택 ───────────────────────────────────────
            # HTML 구조: 커스텀 combobox
            # <button id="myAbsenceWrite_absnTypeSel">연차</button>
            # <ul id="myAbsenceWrite_absnTypeBox">
            #   <li><input type="radio" id="Y" value="Y"><label for="Y">연차</label></li>
            #   <li><input type="radio" id="A" value="A"><label for="A">오전 반차</label></li>
            #   ...
            # </ul>
            absence_type = params["absence_type"]
            print(f"[7/14] 부재명 선택: {absence_type}")

            # 부재명이 이미 "연차"로 선택돼 있더라도 올바른 타입으로 변경
            # 1단계: 현재 선택된 값 확인
            current_label = page.locator("#myAbsenceWrite_absnTypeSel").inner_text().strip()
            print(f"[7/14] 현재 선택값: {current_label}")

            if current_label != absence_type:
                # 드롭다운 열기
                page.locator("#myAbsenceWrite_absnTypeSel").click()
                time.sleep(0.5)
                # label 텍스트로 라디오 버튼 클릭
                page.locator(
                    f"#myAbsenceWrite_absnTypeBox label:has-text('{absence_type}')"
                ).first.click()
                time.sleep(0.5)
                print(f"[7/14] 부재명 변경 완료: {absence_type}")
            else:
                print(f"[7/14] 이미 '{absence_type}' 선택됨 - 스킵")

            _shot(page, "06_absence_type_selected")

            # ── STEP 7: + 버튼 클릭 → 날짜 행 추가 ──────────────────────
            # HTML: <button id="myAbsenceWrite_absnAddBtn" title="추가">
            print("[8/14] + 버튼 클릭 (날짜 행 추가)")
            page.locator("#myAbsenceWrite_absnAddBtn").click()
            time.sleep(1.5)  # 동적 행 생성 + datepicker 바인딩 완료 대기

            # datepicker가 input에 완전히 바인딩될 때까지 추가 대기
            # (hasDatepicker 클래스가 생기면 바인딩 완료)
            for _ in range(10):
                has_dp = page.evaluate(
                    "(function() {"
                    "  var inputs = document.querySelectorAll('.ui-dialog input[placeholder=\"yyyy-mm-dd\"]');"
                    "  return inputs.length > 0 && inputs[0].classList.contains('hasDatepicker');"
                    "})()"
                )
                if has_dp:
                    break
                time.sleep(0.3)
            time.sleep(0.3)
            _shot(page, "07_date_row_added")

            # ── STEP 8: 날짜 입력 ─────────────────────────────────────────
            # [그룹 A] 날짜 1개: "오전 반차", "오후 반차", "오전 반반차", "오후 반반차", "보건 휴가"
            #   → datepicker 0번만 입력, 나머지 그룹웨어 자동
            # [그룹 B] 나머지 전부
            #   → 시작일 항상 입력, start==end 면 종료일 생략(그룹웨어 자동), 다르면 종료일도 입력
            from datetime import date as _date

            _GROUP_A = {"오전 반차", "오후 반차", "오전 반반차", "오후 반반차", "보건 휴가"}
            is_group_a = absence_type in _GROUP_A

            start_date = params["start_date"]
            end_date   = params.get("end_date", "") or ""
            if not end_date.strip():
                end_date = start_date

            if is_group_a:
                print(f"[9/14] 그룹A({absence_type}) → 날짜 1개: {start_date}")
                _fill_datepicker(page, 0, start_date)
                # 입력값 확인
                val = page.locator('.ui-dialog input[placeholder="yyyy-mm-dd"]').nth(0).input_value()
                print(f"[9/14] 날짜 확인: {val}")
            else:
                # 역전 검사
                try:
                    if _date.fromisoformat(start_date) > _date.fromisoformat(end_date):
                        raise ValueError(f"날짜 오류: 시작({start_date}) > 종료({end_date})")
                except ValueError:
                    raise

                print(f"[9/14] 그룹B({absence_type}) → 시작: {start_date}, 종료: {end_date}")
                _fill_datepicker(page, 0, start_date)

                if start_date != end_date:
                    print(f"[9/14] 종료일 입력: {end_date}")
                    _fill_datepicker(page, 1, end_date)
                else:
                    print(f"[9/14] 종료일 생략 (그룹웨어 자동)")

                val_s = page.locator('.ui-dialog input[placeholder="yyyy-mm-dd"]').nth(0).input_value()
                val_e = page.locator('.ui-dialog input[placeholder="yyyy-mm-dd"]').nth(1).input_value()
                print(f"[9/14] 날짜 확인: 시작={val_s}, 종료={val_e}")

            _shot(page, "08_date_filled")

            # ── STEP 9: 사유 입력 ─────────────────────────────────────────
            # HTML: <input id="myAbsenceWrite_input_absnWhy">
            reason = params.get("reason", "개인사유")
            print(f"[10/14] 사유 입력: {reason}")
            page.locator("#myAbsenceWrite_input_absnWhy").fill(reason)
            _shot(page, "09_reason_filled")

            # ── STEP 10: 저장 클릭 ────────────────────────────────────────
            print("[11/14] 저장 클릭")
            _shot(page, "10_before_save")
            page.on("dialog", lambda d: d.accept())

            try:
                with ctx.expect_page(timeout=18000) as popup_info:
                    page.locator("#absnWriteLvr_save").click()
                    page.wait_for_timeout(800)
                    closed = _dismiss_warning_modals(page)
                    if closed:
                        raise RuntimeError("저장 취소됨: 필수 입력 누락")
                popup = popup_info.value
                popup.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_MS)
                try:
                    popup.wait_for_load_state("networkidle", timeout=10000)
                except Exception:
                    pass
                print("[11/14] 결재 작성 팝업 열림")
                _shot(popup, "11_approval_popup")
            except RuntimeError as rte:
                raise rte
            except Exception as save_err:
                print(f"[11/14] 새 팝업 없음 ({save_err}) → 현재 페이지 확인")
                _shot(page, "11_no_popup")
                _dismiss_warning_modals(page)
                page.wait_for_timeout(500)
                all_pages = ctx.pages
                if len(all_pages) > 1:
                    popup = all_pages[-1]
                    popup.wait_for_load_state("domcontentloaded", timeout=TIMEOUT_MS)
                    print(f"[11/14] 팝업 발견: {popup.url}")
                    _shot(popup, "11_approval_popup_found")
                else:
                    popup = None
                    print("[11/14] 저장 완료 (팝업 없이 처리됨)")
                    _shot(page, "11_after_save")

            if popup is None:
                print("[done] 부재 등록 완료 (결재 없음)")
                return True

            # ── STEP 10: 문서 제목 입력 ───────────────────────────────────
            doc_title = f"{absence_type} 신청"
            print(f"[12/14] 문서 제목 입력: {doc_title}")

            title_row = popup.locator(
                "tr:has(th:has-text('제목')), "
                "tr:has(td:has-text('제목'))"
            )
            if title_row.count() > 0:
                title_row.locator("input[type='text']:visible").first.fill(doc_title)
            else:
                popup.locator(
                    "input[type='text']:visible"
                ).first.fill(doc_title)
            _shot(popup, "12_title_filled")

            # ── STEP 11: 결재선 탭 클릭 ───────────────────────────────────
            print("[13/14] 결재선 탭 클릭")
            popup.locator(
                "ul.tab_lst li:has-text('결재선'), "
                "div.tab_area li:has-text('결재선'), "
                "a:has-text('결재선'), "
                "li.tab:has-text('결재선')"
            ).first.click()
            popup.wait_for_load_state("networkidle", timeout=TIMEOUT_MS)
            popup.wait_for_timeout(500)
            _shot(popup, "13_approval_tab")

            # ── STEP 12: 결재선 선택 버튼 → 결재선 모달 ──────────────────
            popup.locator(
                "a:has-text('결재선 선택'), "
                "button:has-text('결재선 선택'), "
                "a:has-text('결재선선택')"
            ).first.click()
            popup.wait_for_timeout(1000)

            popup.wait_for_selector(
                "input[placeholder='부서,사용자명']:visible, "
                "input[placeholder*='사용자명']:visible",
                timeout=TIMEOUT_MS
            )
            _shot(popup, "14_approval_modal")

            # ── STEP 13: 결재자 검색 ──────────────────────────────────────
            # UI 구조 (스크린샷 확정):
            # - 검색창: input[placeholder='부서,사용자명']
            # - 검색버튼: 돋보기 아이콘 버튼
            # - 결과: "• ☐ 박성현 상무이사/Core운영실" 형태 리스트
            # - 하단: 합의☑병렬 | 결재 | 합의☑병렬 | 결재 | ... 슬롯들
            # - 동작: 체크박스 클릭 → 결재 슬롯으로 drag & drop → 확인
            approver_keyword = params["approver_keyword"]
            print(f"[14/14] 결재자 검색: {approver_keyword}")

            # JS로 현재 모달 HTML 구조 덤프 (디버깅용)
            try:
                modal_info = popup.evaluate("""
                    (function() {
                        var dialog = document.querySelector('.ui-dialog');
                        if (!dialog) return 'no dialog';
                        var inputs = dialog.querySelectorAll('input[type="checkbox"]');
                        var btns = dialog.querySelectorAll('button');
                        var btnTexts = Array.from(btns).map(b => b.className + ':' + (b.title||b.innerText||'').trim().substring(0,20));
                        return 'checkboxes:' + inputs.length + ' | btns:' + btnTexts.slice(0,8).join(', ');
                    })()
                """)
                print(f"[14/14] 모달 구조: {modal_info}")
            except Exception:
                pass

            # 검색창 입력
            search_input = popup.locator(
                "input[placeholder='부서,사용자명']:visible, "
                "input[placeholder*='사용자명']:visible, "
                "input[placeholder*='부서']:visible"
            ).first
            search_input.fill(approver_keyword)
            popup.wait_for_timeout(300)

            # 검색 버튼 클릭 (돋보기 🔍 버튼)
            # 스크린샷에서 검색창 오른쪽에 버튼 있음
            try:
                search_btn = popup.locator(
                    "button.btn_srch, button[title='검색'], "
                    "button.btn_search, a.btn_srch, "
                    ".org_search button, .srch_area button, "
                    "button:has(.ico_srch), button:has(.ico_search)"
                ).first
                search_btn.click(timeout=3000)
                print("[14/14] 검색 버튼 클릭 성공")
            except Exception:
                # 검색창에서 Enter
                search_input.press("Enter")
                print("[14/14] Enter로 검색")

            popup.wait_for_timeout(2000)
            _shot(popup, "15_search_result")

            # ── STEP 13-2: 체크박스 Playwright 실제 클릭 ────────────────
            # Console 로그 확인: DynaTree 사용 (isTrusted 이벤트 필요)
            print(f"[14/14] 체크박스 클릭 (Playwright)")

            # 체크박스 목록 출력 (디버그)
            checkbox_info = popup.evaluate("""
                (function() {
                    var allCbs = document.querySelectorAll('input[type="checkbox"]');
                    var info = [];
                    allCbs.forEach(function(cb, i) {
                        var parent = cb.parentElement;
                        var text = parent ? parent.innerText.trim().substring(0,30) : 'no-parent';
                        info.push(i + ':id=' + cb.id + ' val=' + cb.value + ' text=' + text);
                    });
                    return info.join(' || ');
                })()
            """)
            print(f"[14/14] 체크박스 목록: {checkbox_info[:300]}")

            # Playwright로 실제 클릭 (isTrusted=true)
            cb_clicked = False
            try:
                all_cbs = popup.locator("input[type='checkbox']").all()
                for cb in all_cbs:
                    try:
                        box = cb.bounding_box()
                        if not box:
                            continue
                        # 부모 텍스트 확인
                        parent_text = cb.evaluate("el => el.closest('li,div,tr') ? el.closest('li,div,tr').innerText : ''")
                        if approver_keyword in parent_text:
                            cb.click(timeout=3000)
                            cb_clicked = True
                            print(f"[14/14] Playwright 체크박스 클릭: {parent_text[:30]}")
                            break
                    except Exception:
                        continue
            except Exception as e:
                print(f"[14/14] Playwright CB 실패: {e}")

            if not cb_clicked:
                # fallback JS click
                clicked = popup.evaluate(f"""
                    (function() {{
                        var allCbs = document.querySelectorAll('input[type="checkbox"]');
                        for (var i = 0; i < allCbs.length; i++) {{
                            var cb = allCbs[i];
                            var parent = cb.closest('li, tr, div');
                            var text = parent ? parent.innerText : '';
                            if (text.indexOf('{approver_keyword}') >= 0) {{
                                cb.click();
                                return 'js clicked ' + text.trim().substring(0,25);
                            }}
                        }}
                        return 'not_found';
                    }})()
                """)
                print(f"[14/14] JS 체크박스: {clicked}")

            popup.wait_for_timeout(1000)
            _shot(popup, "16_checkbox_checked")

            # ── STEP 13-3: DynaTree 노드 드래그 ─────────────────────────
            # Console 로그 확인:
            # - DynaTree 라이브러리 사용
            # - 드래그 소스: DynaTree의 'a.dynatree-title' 또는 'span.dynatree-node' (li 전체 X)
            # - 타겟: #af_2
            print("[14/14] DynaTree 노드 드래그")

            # DynaTree 노드 위치 파악
            node_info = popup.evaluate(f"""
                (function() {{
                    // 체크된 DynaTree 노드의 a/span 위치 파악
                    var checked = document.querySelectorAll('input[type="checkbox"]:checked');
                    var nodes = [];
                    for (var cb of checked) {{
                        var li = cb.closest('li');
                        if (!li) continue;
                        var aTag = li.querySelector('a.dynatree-title');
                        var spanNode = li.querySelector('span.dynatree-node');
                        var spanTitle = li.querySelector('span.dynatree-title');
                        var liR = li.getBoundingClientRect();
                        var aR = aTag ? aTag.getBoundingClientRect() : null;
                        var snR = spanNode ? spanNode.getBoundingClientRect() : null;
                        nodes.push({{
                            text: li.innerText.trim().substring(0,25),
                            liPos: [Math.round(liR.left+liR.width/2), Math.round(liR.top+liR.height/2)],
                            aPos: aR ? [Math.round(aR.left+aR.width/2), Math.round(aR.top+aR.height/2)] : null,
                            spanPos: snR ? [Math.round(snR.left+snR.width/2), Math.round(snR.top+snR.height/2)] : null,
                            aClass: aTag ? aTag.className : 'no-a',
                            spanClass: spanNode ? spanNode.className : 'no-span'
                        }});
                    }}
                    var af2 = document.getElementById('af_2');
                    var af2R = af2 ? af2.getBoundingClientRect() : null;
                    return {{
                        nodes: nodes,
                        af2: af2R ? [Math.round(af2R.left+af2R.width/2), Math.round(af2R.top+af2R.height/2)] : [271,590]
                    }};
                }})()
            """)
            print(f"[14/14] DynaTree 노드: {node_info}")

            dx = node_info['af2'][0] if node_info else 271
            dy = node_info['af2'][1] if node_info else 590

            # 소스 좌표: a태그 > span > li 순
            src_pos = None
            if node_info and node_info.get('nodes'):
                for n in node_info['nodes']:
                    if approver_keyword in n.get('text', '') or True:
                        src_pos = n.get('aPos') or n.get('spanPos') or n.get('liPos')
                        break

            sx = src_pos[0] if src_pos else 698
            sy = src_pos[1] if src_pos else 184
            print(f"[14/14] drag: ({sx},{sy}) → ({dx},{dy})")

            drag_success = False

            # 방법1: DynaTree a태그로 drag_and_drop
            for src_sel in [
                f"a.dynatree-title:has-text('{approver_keyword}')",
                f"span.dynatree-node:has-text('{approver_keyword}')",
                f"li:has(input:checked):has-text('{approver_keyword}') a",
                f"li:has(input:checked):has-text('{approver_keyword}') span",
                f"li:has(input:checked):has-text('{approver_keyword}')",
            ]:
                try:
                    popup.drag_and_drop(src_sel, "#af_2", timeout=5000)
                    drag_success = True
                    print(f"[14/14] drag_and_drop 성공: {src_sel}")
                    break
                except Exception as e:
                    print(f"[14/14] {src_sel[:40]} 실패: {str(e)[:40]}")

            if not drag_success:
                # 방법2: mouse API (좌표 기반)
                print(f"[14/14] mouse drag: ({sx},{sy})→({dx},{dy})")
                try:
                    popup.mouse.move(sx, sy)
                    popup.wait_for_timeout(300)
                    popup.mouse.down()
                    popup.wait_for_timeout(600)
                    popup.mouse.move(sx+3, sy+3)
                    popup.wait_for_timeout(100)
                    popup.mouse.move(sx+6, sy+6)
                    popup.wait_for_timeout(100)
                    for i in range(1, 31):
                        nx = sx+6 + (dx-sx-6)*i/30
                        ny = sy+6 + (dy-sy-6)*i/30
                        popup.mouse.move(nx, ny)
                        popup.wait_for_timeout(15)
                    popup.wait_for_timeout(500)
                    popup.mouse.up()
                    popup.wait_for_timeout(2000)
                    drag_success = True
                    print("[14/14] mouse drag 완료")
                except Exception as e:
                    print(f"[14/14] mouse API 실패: {e}")

            popup.wait_for_timeout(1000)
            _shot(popup, "16a_after_drag")

            popup.wait_for_timeout(500)
            _shot(popup, "16_approver_after_drag")

            # ── STEP 14: 확인 버튼 클릭 ──────────────────────────────────
            # 확인 버튼: _confirmBtn 클래스, 드래그 성공 후 활성화됨
            # x=0,y=0 이면 아직 비활성 → 드래그 후 좌표 재조회
            print("[14/14] 확인 버튼 클릭")
            confirm_info = popup.evaluate("""
                (function() {
                    var btn = document.querySelector('button._confirmBtn');
                    if (!btn) return null;
                    var r = btn.getBoundingClientRect();
                    return {x: Math.round(r.left+r.width/2), y: Math.round(r.top+r.height/2),
                            w: Math.round(r.width), h: Math.round(r.height), visible: r.width>0};
                })()
            """)
            print(f"[14/14] 확인 버튼: {confirm_info}")

            if confirm_info and confirm_info.get('x', 0) > 0:
                popup.mouse.click(confirm_info['x'], confirm_info['y'])
                print(f"[14/14] 확인 mouse.click({confirm_info['x']}, {confirm_info['y']}) ✅")
            else:
                # x=0,y=0 → 버튼이 숨겨진 상태 = 드래그 실패
                # 모달 하단 확인 버튼 위치: 모달(x=75,y=33,w=850,h=756) 기준
                # 스크린샷에서 확인: x≈471, y≈752
                popup.mouse.click(471, 752)
                print("[14/14] 확인 fallback click(471, 752)")

            popup.wait_for_timeout(1000)

            # alert 처리 (결재선명 입력 요구 등)
            alert_handled = popup.evaluate("""
                (function() {
                    // 최상단 ui-dialog (alert) 찾기
                    var dialogs = document.querySelectorAll('.ui-dialog');
                    for (var d of dialogs) {
                        var txt = (d.innerText||'');
                        if (txt.indexOf('결재선명') >= 0 || txt.indexOf('입력') >= 0) {
                            var btn = d.querySelector('button');
                            if (btn) { btn.click(); return '결재선명 alert 처리'; }
                        }
                    }
                    return 'no alert';
                })()
            """)
            print(f"[14/14] alert 처리: {alert_handled}")
            popup.wait_for_timeout(500)

            # 모달 닫힘 대기
            try:
                popup.wait_for_selector("#eapLineSelect", state="hidden", timeout=8000)
                print("[14/14] 결재선 선택 모달 닫힘 ✅")
            except Exception:
                print("[14/14] 모달 닫힘 timeout - 강제 진행")

            popup.wait_for_timeout(2000)
            _shot(popup, "17_after_confirm")

            # ── STEP 15: 결재상신 클릭 ────────────────────────────────────
            # 결재 작성 팝업 상단 "결재상신" 버튼
            # HTML 소스에서 확인: id="eapDocPopup_writeWrap" 내 버튼
            print("[15/15] 결재상신 클릭")

            # 결재상신 버튼 JS로 DOM에서 직접 찾아 클릭
            submit_result = popup.evaluate("""
                (function() {
                    // 텍스트로 버튼 찾기
                    var btns = document.querySelectorAll('button, a');
                    for (var btn of btns) {
                        var txt = (btn.innerText || btn.textContent || '').trim();
                        if (txt === '결재상신' || txt.indexOf('결재상신') >= 0) {
                            var rect = btn.getBoundingClientRect();
                            return {found: true, text: txt, class: btn.className, visible: rect.width > 0, x: rect.left+rect.width/2, y: rect.top+rect.height/2};
                        }
                    }
                    return {found: false, totalBtns: btns.length};
                })()
            """)
            print(f"[15/15] 결재상신 버튼 JS 탐색: {submit_result}")

            try:
                if submit_result and submit_result.get('found') and submit_result.get('visible'):
                    sx = submit_result['x']
                    sy = submit_result['y']
                    popup.mouse.click(sx, sy)
                    print(f"[15/15] 결재상신 mouse.click({sx:.0f}, {sy:.0f}) 완료")
                else:
                    submit_btn = popup.locator(
                        "button:has-text('결재상신'), "
                        "a:has-text('결재상신')"
                    ).first
                    submit_btn.wait_for(state="visible", timeout=10000)
                    submit_btn.click()
                    print("[15/15] 결재상신 locator 클릭 완료")
            except Exception as e:
                print(f"[15/15] 결재상신 실패: {e}")
                raise

            # ── STEP 16: "상신하겠습니까?" 확인 다이얼로그 처리 ──────────
            # 결재상신 클릭 후 확인 다이얼로그가 뜸 → 확인 버튼 클릭 필수
            popup.wait_for_timeout(1500)
            _shot(popup, "18_before_confirm_dialog")

            confirm_dialog_result = popup.evaluate("""
                (function() {
                    // "상신하겠습니까?" 다이얼로그 찾기
                    var dialogs = document.querySelectorAll('.ui-dialog');
                    for (var d of dialogs) {
                        var txt = (d.innerText || '').trim();
                        if (txt.indexOf('상신') >= 0 || txt.indexOf('하겠습니까') >= 0) {
                            // 확인 버튼 클릭
                            var btns = d.querySelectorAll('button');
                            for (var btn of btns) {
                                var btnTxt = (btn.innerText || '').trim();
                                if (btnTxt === '확인' || btnTxt.indexOf('확인') >= 0) {
                                    var r = btn.getBoundingClientRect();
                                    return {found: true, msg: txt.substring(0,20),
                                            x: Math.round(r.left+r.width/2),
                                            y: Math.round(r.top+r.height/2)};
                                }
                            }
                        }
                    }
                    // 일반 confirm dialog (alert)
                    var allBtns = document.querySelectorAll('.ui-dialog button');
                    for (var b of allBtns) {
                        if ((b.innerText||'').trim() === '확인') {
                            var r = b.getBoundingClientRect();
                            return {found: true, msg: 'generic confirm',
                                    x: Math.round(r.left+r.width/2),
                                    y: Math.round(r.top+r.height/2)};
                        }
                    }
                    return {found: false};
                })()
            """)
            print(f"[16/16] 상신 확인 다이얼로그: {confirm_dialog_result}")

            if confirm_dialog_result and confirm_dialog_result.get('found'):
                cx = confirm_dialog_result['x']
                cy = confirm_dialog_result['y']
                popup.mouse.click(cx, cy)
                print(f"[16/16] 상신 확인 클릭 ({cx}, {cy}) ✅")
                popup.wait_for_timeout(3000)
            else:
                # 다이얼로그가 안 보이면 Playwright locator로 시도
                try:
                    confirm_btn = popup.locator("button:has-text('확인')").last
                    confirm_btn.click(timeout=5000)
                    print("[16/16] 상신 확인 locator 클릭 ✅")
                    popup.wait_for_timeout(3000)
                except Exception as e:
                    print(f"[16/16] 확인 버튼 없음 (이미 상신됨 가능성): {e}")
                    popup.wait_for_timeout(2000)

            _shot(popup, "18_submitted")
            print("[done] 그룹웨어 부재 신청 완료")
            return True

        except Exception as e:
            _shot(page, "error_main")
            if popup is not None:
                try:
                    _shot(popup, "error_popup")
                except Exception:
                    pass
            raise e

        finally:
            ctx.close()
            browser.close()
