$VenvPython = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"

if (!(Test-Path $VenvPython)) {
    python -m venv (Join-Path $PSScriptRoot ".venv")
}

& $VenvPython -m pip install -r (Join-Path $PSScriptRoot "requirements.txt")
& $VenvPython -m uvicorn app.main:app --host 127.0.0.1 --port 8200 --reload
