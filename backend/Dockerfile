FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY main.py providers.py ./
USER nobody
EXPOSE 8787
CMD ["uvicorn", "main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8787"]
