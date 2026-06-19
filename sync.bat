@echo off
chcp 65001 > nul
echo =======================================
echo  ClassRPG GitHub 자동 동기화 스크립트
echo =======================================

:: 1. 변경사항 추가
echo [1/3] 변경된 파일을 준비(Stage)하는 중...
git add .

:: 2. 커밋 메시지 입력 받기 (입력하지 않으면 기본 메시지 사용)
set /p msg="커밋 메시지를 입력하세요 (기본값: 'Update code'): "
if "%msg%"=="" set msg=Update code

echo [2/3] 변경사항을 커밋(Commit)하는 중...
git commit -m "%msg%"

:: 3. 깃허브로 푸시
echo [3/3] GitHub로 코드 업로드(Push)하는 중...
git push origin main

echo =======================================
echo  동기화가 완료되었습니다!
echo =======================================
pause
