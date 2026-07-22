import os
import shutil

old_pkg = "com.example.usbtransferapp"
new_pkg = "com.example.securequicktransferapp"
old_dir_name = "usbtransferapp"
new_dir_name = "securequicktransferapp"

# 1. Replace content in files
for root, dirs, files in os.walk('.', topdown=False):
    # Skip build directories
    if 'build' in root.split(os.sep):
        continue

    for f in files:
        if f.endswith(('.kt', '.xml', '.kts', '.pro')):
            filepath = os.path.join(root, f)
            try:
                with open(filepath, 'r', encoding='utf-8') as file:
                    content = file.read()
                if old_pkg in content:
                    content = content.replace(old_pkg, new_pkg)
                    with open(filepath, 'w', encoding='utf-8') as file:
                        file.write(content)
                    print(f"Updated content in {filepath}")
            except Exception as e:
                print(f"Failed to process {filepath}: {e}")

# 2. Rename directories
for root, dirs, files in os.walk('.', topdown=False):
    if 'build' in root.split(os.sep):
        continue
        
    for d in dirs:
        if d == old_dir_name:
            old_path = os.path.join(root, d)
            new_path = os.path.join(root, new_dir_name)
            os.rename(old_path, new_path)
            print(f"Renamed directory {old_path} to {new_path}")
