#!/usr/bin/env python3


from cProfile import label
import sys
import re
from turtle import color
import matplotlib
import matplotlib.pyplot as plt


matplotlib.use("QtAgg")


def read_file_line_by_line(file_path: str, callback=None):
    with open(file_path, mode="r") as file:
        while True:
            line = file.readline()

            if not line:
                break

            if callback:
                callback(line)


def read_file_line_by_line_into_list(file_path: str) -> list:
    result = []

    def _tmp_callback(line: str):
        result.append(line.strip())

    read_file_line_by_line(file_path, _tmp_callback)
    return result


def plot_drone_attitude_changes(file_path: str):
    print(file_path)

    drone_attitudes = []
    target_attitude = []
    headset_attitudes = []
    current_drone_attitude = None
    current_headset_attitude = None
    
    timestamp_regex = r'^(?P<time>.*?\s.*?)\s'

    # 2025-09-19 15:52:16.989	I	DroneStatusMonitor		Drone Attitude (Y/R/P): 75.70 / -0.30 / -0.20
    attitude_regex = (
        r"^.*Drone\s*?Attitude.*\(Y/R/P\):\s*?(?P<attitude>.*?)/.*$"
    )
    # 2025-09-22 16:12:51.512	D	VirtualDroneControllerKt		Sending advanced stick param to the drone: {"pitch":0,"roll":0,"yaw":-144.6,"verticalThrottle":0,"verticalControlMode":0,"rollPitchControlMode":1,"yawControlMode":0,"rollPitchCoordinateSystem":0}
    sent_target_orientation_regex = r'^.*Sending\sadvanced\sstick\sparam\sto\sthe\sdrone.*yaw":(?P<target_attitude>.*?),.*'

    # 2025-09-19 15:53:13.962	D	ControlViaHeadset		Drone current orientation: 26.200000000000003, Headset current orientation: 31.585398197174072, Shortest angle is: -5.385398197174027, Maximum angle change: 11.55
    drone_attitudes_regex = r'^.*Drone\s*?current\s*?orientation:(?P<drone_attitude>.*?),\s*?Headset\s*?current\s*?orientation:(?P<headset_attitude>.*?),\s*?Shortest\s*?angle\s*?is:(?P<shortest_angle>.*?),\s*?Maximum\s*?angle\s*?change:(?P<change>.*)$'

    # 2025-09-22 16:12:51.510	D	CameraStreamVM$onReceivedData		onControllerStatusData: {"benchmarkPosition":{"x":-3.8348565,"y":1.7451186,"z":0.5193311},"benchmarkRotation":{"x":357.01654,"y":54.776726,"z":0.12363679},"benchmarkSampleTimestamp":1758514371304,"currentPosition":{"x":-3.8348565,"y":1.7451186,"z":0.5193311},"currentRotation":{"x":357.01654,"y":54.776726,"z":0.12363679},"lastPosition":{"x":-3.8348565,"y":1.7451186,"z":0.5193311},"lastRotation":{"x":357.01654,"y":54.776726,"z":0.12363679},"leftThumbStickValue":{"x":0.0,"y":0.0},"rightThumbStickValue":{"x":0.0,"y":0.0},"sampleTimestamp":1758514371306}
    headset_orientation_regex = r'^.*CameraStreamVM\$onReceivedData.*onControllerStatusData.*benchmarkRotation.*"y":(?P<headset_b_attitude>.*?),.*currentRotation.*?"y":(?P<headset_c_attitude>.*?),'

    def filter_out_drone_attitude_changes(line: str):
        result = re.search(attitude_regex, line)
        nonlocal current_drone_attitude
        nonlocal current_headset_attitude
        if result:
            print(line)
            current_drone_attitude = int(float(result["attitude"]))
        else:
            result = re.search(sent_target_orientation_regex, line)
            if result:
                sent_target = int(float(result['target_attitude'].strip()))
                target_attitude.append(sent_target)
                if current_drone_attitude is not None:
                    drone_attitudes.append(current_drone_attitude)
                    current_drone_attitude = None
                if current_headset_attitude is not None:
                    headset_attitudes.append(current_headset_attitude)
                    current_headset_attitude = None
            else:
                result = re.search(headset_orientation_regex, line)
                if result:
                    headset_b_attitude = float(result['headset_b_attitude'])
                    headset_c_attitude = float(result['headset_c_attitude'])
                    current_headset_attitude = int((headset_c_attitude - headset_b_attitude) + 360) % 360 - 180

    read_file_line_by_line(file_path, filter_out_drone_attitude_changes)

    plt.title("Drone Attitude Changes")
    plt.xlabel("Time")
    plt.ylabel("Attitude")
    plt.plot(
        [x + 1 for x in range(len(drone_attitudes))],
        [abs(x) for x in drone_attitudes],
        color='red',
        label="Drone Attitude"
    )
    plt.plot(
        [x + 1 for x in range(len(target_attitude))],
        [abs(x) for x in target_attitude],
        color='orange',
        label="Sent Attitude"
    )
    plt.plot(
        [x + 1 for x in range(len(headset_attitudes))],
        [abs(x) for x in headset_attitudes],
        color='green',
        label="Headset Attitude"
    )
    plt.legend()
    plt.tight_layout()
    plt.show()


if __name__ == "__main__":
    plot_drone_attitude_changes("logs/2025_09_" + sys.argv[1] + ".log")
