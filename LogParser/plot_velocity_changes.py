#!/usr/bin/env python3

import sys
import re
import matplotlib.pyplot as plt


def parse_log(filepath) -> list:
    # 2025-10-02 15:07:10.978	U	ControlViaHeadset		Headset position changes from (0.0000, 0.0000) to (-0.0004, 1.5806)

    from log_parse import read_file_line_by_line
    
    result_list = []
    position_change_regex = r'^.*ControlViaHeadset.*from\s\((?P<from_x>.*?),\s(?P<from_y>.*?)\)\sto\s\((?P<to_x>.*?),\s(?P<to_y>.*?)\)'
    def read_position_changes(line):
        tmp = re.search(position_change_regex, line)
        
        if tmp is not None:
            result_list.append({
                "from": {'x': float(tmp['from_x'].strip()), 'y': float(tmp['from_y'].strip())},
                "to": {'x': float(tmp['to_x'].strip()), 'y': float(tmp['to_y'].strip())}
            }) 
    read_file_line_by_line(filepath, read_position_changes)
    return result_list


def plot_velocity_changes(result):
    positions = [{'x': 0, 'y': 0}]
    
    for tmp in result:
        positions.append(
            tmp['to']
        )
    
    plt.plot(
        [x['x'] for x in positions],
        [x['y'] for x in positions]
    )
    plt.xlabel("X")
    plt.ylabel("Y")
    plt.tight_layout()
    plt.show()


if __name__ == "__main__":
    result = parse_log(f"logs/drone_headset_velocity_changes_2025_10_02_16_00.log")

    plot_velocity_changes(result)