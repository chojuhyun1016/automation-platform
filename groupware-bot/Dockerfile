FROM mcr.microsoft.com/playwright/python:v1.42.0-jammy

WORKDIR /app

# stdout 버퍼링 비활성화 — CloudWatch 로그 즉시 전달
ENV PYTHONUNBUFFERED=1
ENV PYTHONDONTWRITEBYTECODE=1

# 의존성 먼저 설치 (캐시 활용)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Playwright Chromium 브라우저 설치
RUN playwright install chromium --with-deps

# 소스 복사
COPY . .

CMD ["python", "-u", "main.py"]
