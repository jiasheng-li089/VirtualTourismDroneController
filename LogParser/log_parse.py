#!/usr/bin/env python3


def read_file_line_by_line(file_path: str, callback = None):
    with open(file_path, mode='r') as file:
        while True:
            line = file.readline()
            
            if not line:
                break
                
            if callback:
                callback()


def read_file_line_by_line_into_list(file_path: str) -> list:
    result = []
    
    def _tmp_callback(line: str):
        result.append(line.strip())
    
    read_file_line_by_line(file_path, _tmp_callback)
    return result


def main():
    print("Hello from logparser!")


if __name__ == "__main__":
    main()
