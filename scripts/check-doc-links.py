from pathlib import Path
import re
from urllib.parse import unquote

root = Path(__file__).resolve().parents[1]
broken = []
for doc in [root / "README.md", *sorted((root / "docs").rglob("*.md"))]:
    content = doc.read_text(encoding="utf-8")
    for match in re.finditer(r"!?\[[^\]]*\]\(([^)]+)\)", content):
        target = match.group(1).split("#", 1)[0].strip("<>")
        if not target or re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I):
            continue
        path = (doc.parent / unquote(target)).resolve()
        if not path.exists():
            broken.append(f"{doc.relative_to(root)}: {target}")
if broken:
    print("\n".join(broken))
    raise SystemExit(1)
print("All local Markdown links and image paths resolve.")
