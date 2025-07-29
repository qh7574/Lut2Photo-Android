import numpy as np
from PIL import Image, ImageOps
import argparse
import sys
import os
import time
import random
import threading
from watchdog.observers import Observer
from watchdog.events import FileSystemEventHandler

class LutProcessor:
    def __init__(self, lut_path, strength=60, quality=90, dither=None):
        self.lut_path = lut_path
        self.strength = strength
        self.quality = quality
        self.dither = dither
        self.lut, self.lut_size = self.load_cube_lut(lut_path)
        
    def load_cube_lut(self, file_path):
        """加载CUBE格式的LUT文件并返回3D数组和LUT尺寸"""
        with open(file_path, 'r') as f:
            lines = [line.strip() for line in f if line.strip()]

        size = None
        data_start = 0
        for i, line in enumerate(lines):
            if line.startswith('LUT_3D_SIZE'):
                size = int(line.split()[1])
                data_start = i + 1
                break

        if not size:
            raise ValueError("LUT_3D_SIZE未在CUBE文件中找到")

        data = []
        for line in lines[data_start:]:
            if line.startswith('#'):
                continue
            parts = line.split()
            if len(parts) == 3:
                try:
                    r, g, b = map(float, parts)
                    data.append((r, g, b))
                except:
                    continue

        expected_length = size ** 3
        if len(data) != expected_length:
            raise ValueError(f"需要{expected_length}个数据点，实际找到{len(data)}个")

        # 创建3D LUT数组（形状为[尺寸][尺寸][尺寸][3]）
        lut = np.zeros((size, size, size, 3), dtype=np.float32)
        index = 0
        for b in range(size):
            for g in range(size):
                for r in range(size):
                    lut[b, g, r] = data[index]
                    index += 1
        return lut, size

    def trilinear_interpolation(self, normalized):
        """使用三线性插值应用LUT"""
        r, g, b = normalized[..., 0], normalized[..., 1], normalized[..., 2]
        scale = self.lut_size - 1
        r_idx = r * scale
        g_idx = g * scale
        b_idx = b * scale
        
        # 计算整数和小数部分
        r0 = np.floor(r_idx).astype(np.int32)
        g0 = np.floor(g_idx).astype(np.int32)
        b0 = np.floor(b_idx).astype(np.int32)
        
        r1 = np.minimum(r0 + 1, scale).astype(np.int32)
        g1 = np.minimum(g0 + 1, scale).astype(np.int32)
        b1 = np.minimum(b0 + 1, scale).astype(np.int32)
        
        r_d = r_idx - r0
        g_d = g_idx - g0
        b_d = b_idx - b0
        
        # 获取8个角点的值
        c000 = self.lut[b0, g0, r0]
        c001 = self.lut[b0, g0, r1]
        c010 = self.lut[b0, g1, r0]
        c011 = self.lut[b0, g1, r1]
        c100 = self.lut[b1, g0, r0]
        c101 = self.lut[b1, g0, r1]
        c110 = self.lut[b1, g1, r0]
        c111 = self.lut[b1, g1, r1]
        
        # 在r方向插值
        c00 = c000 * (1 - r_d[..., np.newaxis]) + c001 * r_d[..., np.newaxis]
        c01 = c010 * (1 - r_d[..., np.newaxis]) + c011 * r_d[..., np.newaxis]
        c10 = c100 * (1 - r_d[..., np.newaxis]) + c101 * r_d[..., np.newaxis]
        c11 = c110 * (1 - r_d[..., np.newaxis]) + c111 * r_d[..., np.newaxis]
        
        # 在g方向插值
        c0 = c00 * (1 - g_d[..., np.newaxis]) + c01 * g_d[..., np.newaxis]
        c1 = c10 * (1 - g_d[..., np.newaxis]) + c11 * g_d[..., np.newaxis]
        
        # 在b方向插值
        output = c0 * (1 - b_d[..., np.newaxis]) + c1 * b_d[..., np.newaxis]
        
        return output

    def apply_floyd_steinberg_dithering(self, image):
        """应用优化的Floyd-Steinberg抖动减少色彩断层"""
        img_float = image.astype(np.float32) / 255.0
        height, width, channels = img_float.shape
        
        # 创建输出数组
        output = np.zeros_like(img_float)
        
        # 创建误差传播缓冲区
        error_buffer = np.zeros((height, width, channels), dtype=np.float32)
        
        # 遍历所有像素
        for y in range(height):
            for x in range(width):
                # 获取当前像素值加上之前累积的误差
                current_pixel = img_float[y, x] + error_buffer[y, x]
                
                # 量化（抖动后的值）
                new_pixel = np.round(current_pixel * 255) / 255
                output[y, x] = new_pixel
                
                # 计算量化误差
                quant_error = current_pixel - new_pixel
                
                # 传播误差到相邻像素（仅处理有效位置）
                if x < width - 1:
                    error_buffer[y, x+1] += quant_error * 7/16
                if y < height - 1:
                    if x > 0:
                        error_buffer[y+1, x-1] += quant_error * 3/16
                    error_buffer[y+1, x] += quant_error * 5/16
                    if x < width - 1:
                        error_buffer[y+1, x+1] += quant_error * 1/16
        
        return (output * 255).astype(np.uint8)

    def apply_random_dithering(self, image):
        """应用随机抖动减少色彩断层"""
        # 添加随机噪声
        noise = np.random.uniform(-0.5, 0.5, image.shape).astype(np.float32)
        noisy_image = image.astype(np.float32) + noise
        
        # 量化并限制范围
        return np.clip(np.round(noisy_image), 0, 255).astype(np.uint8)

    def apply_lut_with_strength(self, original, lut_output):
        """根据强度混合原始图像和LUT处理后的图像"""
        strength = np.clip(self.strength / 100.0, 0.0, 1.0)  # 转换为0-1范围
        
        # 使用非线性混合减少色彩断层
        blended = np.sqrt(
            (1 - strength) * np.square(original) + 
            strength * np.square(lut_output)
        )
        
        return np.clip(blended, 0, 255).astype(np.uint8)

    def process_image(self, input_path, output_path):
        """处理单张图片"""
        try:
            print(f"开始处理: {os.path.basename(input_path)}")
            start_time = time.time()
            
            # 读取源文件并处理EXIF
            input_img = Image.open(input_path).convert("RGB")
            input_img = ImageOps.exif_transpose(input_img)
            exif_data = input_img.getexif()
            input_array = np.array(input_img).astype(np.float32)
            
            # 归一化图像数据
            normalized = np.clip(input_array / 255.0, 0.0, 1.0)
            
            # 应用LUT（使用三线性插值）
            lut_output = self.trilinear_interpolation(normalized)
            lut_output = (lut_output * 255.0).astype(np.float32)
            
            # 混合原始图像和LUT处理后的图像
            final_output = self.apply_lut_with_strength(input_array, lut_output)
            
            # 应用抖动处理
            if self.dither:
                if self.dither == 'floyd':
                    final_output = self.apply_floyd_steinberg_dithering(final_output)
                else:  # random
                    final_output = self.apply_random_dithering(final_output)
            
            # 确保输出目录存在
            os.makedirs(os.path.dirname(output_path), exist_ok=True)
            
            # 保存结果
            Image.fromarray(final_output.astype(np.uint8)).save(
                output_path,
                quality=self.quality,
                subsampling=0,
                exif=exif_data.tobytes()
            )
            
            elapsed = time.time() - start_time
            print(f"处理完成: {os.path.basename(output_path)} (耗时: {elapsed:.2f}秒)")
            return True
        
        except Exception as e:
            print(f"处理失败: {os.path.basename(input_path)} - {str(e)}")
            return False


class FolderMonitor(FileSystemEventHandler):
    def __init__(self, input_dir, output_dir, processor):
        self.input_dir = input_dir
        self.output_dir = output_dir
        self.processor = processor
        self.processed_files = set()
        
        # 初始化时处理已有文件
        for file in os.listdir(input_dir):
            if self.is_image_file(file):
                input_path = os.path.join(input_dir, file)
                output_path = os.path.join(output_dir, file)
                self.processed_files.add(file)
                self.processor.process_image(input_path, output_path)
    
    def is_image_file(self, filename):
        """检查文件是否为支持的图像格式"""
        return filename.lower().endswith(('.jpg', '.jpeg', '.png', '.tiff', '.bmp'))
    
    def on_created(self, event):
        """处理新文件创建事件"""
        if not event.is_directory:
            file_path = event.src_path
            filename = os.path.basename(file_path)
            
            # 等待文件完全写入（避免处理不完整的文件）
            time.sleep(1)
            
            if self.is_image_file(filename) and filename not in self.processed_files:
                output_path = os.path.join(self.output_dir, filename)
                self.processed_files.add(filename)
                
                # 在新线程中处理图片，避免阻塞监控
                threading.Thread(
                    target=self.processor.process_image,
                    args=(file_path, output_path)
                ).start()


def start_monitoring(input_dir, output_dir, lut_path, strength=60, quality=90, dither=None):
    """启动文件夹监控服务"""
    # 创建LUT处理器
    processor = LutProcessor(lut_path, strength, quality, dither)
    
    # 创建文件夹监控器
    event_handler = FolderMonitor(input_dir, output_dir, processor)
    observer = Observer()
    observer.schedule(event_handler, input_dir, recursive=False)
    
    print(f"开始监控文件夹: {input_dir}")
    print(f"输出目录: {output_dir}")
    print(f"LUT文件: {lut_path}")
    print(f"强度: {strength}%, 质量: {quality}, 抖动: {dither or '无'}")
    print("按 Ctrl+C 停止监控...")
    
    try:
        observer.start()
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        observer.stop()
        print("\n监控已停止")
    observer.join()


if __name__ == "__main__":
    # 创建命令行参数解析器
    parser = argparse.ArgumentParser(description='LUT图像处理器与文件夹监控')
    
    # 监控模式参数
    parser.add_argument('--monitor', action='store_true', help='启用文件夹监控模式')
    parser.add_argument('--input-dir', help='监控的输入文件夹路径')
    parser.add_argument('--output-dir', help='处理后的输出文件夹路径')
    
    # 单文件处理参数
    parser.add_argument('--lut', help='LUT文件路径')
    parser.add_argument('--input', help='输入图片路径')
    parser.add_argument('--output', help='输出图片路径')
    parser.add_argument('--strength', type=int, default=60, 
                        help='LUT效果强度 0-100 (默认: 60)')
    parser.add_argument('--quality', type=int, default=90, 
                        help='JPEG输出质量 1-100 (默认: 90)')
    parser.add_argument('--dither', choices=['floyd', 'random'], 
                        help='抖动类型: floyd(Floyd-Steinberg)或random(随机)')
    
    args = parser.parse_args()
    
    # 验证参数范围
    if not (0 <= args.strength <= 100):
        raise ValueError("强度值必须在0-100范围内")
    
    if not (1 <= args.quality <= 100):
        raise ValueError("质量值必须在1-100范围内")

    try:
        if args.monitor:
            # 文件夹监控模式
            if not args.input_dir or not args.output_dir or not args.lut:
                raise ValueError("监控模式需要指定 --input-dir, --output-dir 和 --lut")
                
            start_monitoring(
                args.input_dir,
                args.output_dir,
                args.lut,
                args.strength,
                args.quality,
                args.dither
            )
        else:
            # 单文件处理模式
            if not args.input or not args.output or not args.lut:
                raise ValueError("单文件模式需要指定 --input, --output 和 --lut")
                
            processor = LutProcessor(
                args.lut,
                args.strength,
                args.quality,
                args.dither
            )
            processor.process_image(args.input, args.output)
            
    except Exception as e:
        print(f"错误: {str(e)}")
        sys.exit(1)