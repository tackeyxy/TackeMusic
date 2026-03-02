import json
import requests
import os
import re

# 下载目录
DOWNLOAD_DIR = "download"

def get_music_list(keywords: str, page_index: int = 0, page_size: int = 30):
    """
    搜索酷我音乐，返回歌曲列表
    :param keywords: 搜索关键词
    :param page_index: 页码（从0开始）
    :param page_size: 每页数量
    :return: 列表，每个元素包含 id, name, artists
    """
    url = f"http://search.kuwo.cn/r.s?client=kt&all={keywords}&pn={page_index}&rn={page_size}&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36',
        'Accept-Encoding': 'gzip',
        'Host': 'search.kuwo.cn'
    }

    try:
        response = requests.get(url, headers=headers, timeout=10)
        response.raise_for_status()
        result = response.json()
    except Exception as e:
        print(f"搜索请求失败: {e}")
        return []

    abslist = result.get('abslist', [])
    if not abslist:
        print("没有找到相关歌曲")
        return []

    song_list = []
    for idx, item in enumerate(abslist, 1):
        song_id = item.get('DC_TARGETID')
        artist = item.get('ARTIST', '未知')
        song_name = item.get('SONGNAME', '未知')
        print(f"{idx}. {song_id}\t{artist}\t{song_name}")
        song_list.append({
            'index': idx,
            'id': str(song_id),
            'name': song_name,
            'artists': artist
        })

    print(f"共找到 {len(abslist)} 首歌曲")
    return song_list


def get_song_detail(song_id: str, quality: str) -> dict:
    """
    获取歌曲详细信息（下载链接、封面、歌词等）
    :param song_id: 歌曲ID
    :param quality: 音质 128k/320k/flac/flac24bit
    :return: 包含歌曲详细信息的字典，若失败则返回空字典
    """
    url = "http://103.207.68.185:9630/tunefree/parse"
    payload = {
        "platform": "kuwo",
        "ids": song_id,
        "quality": quality
    }
    headers = {
        'user-agent': 'Dart/3.9 (dart:io)',
        'content-type': 'application/json',
        'accept-encoding': 'gzip'
    }

    try:
        response = requests.post(url, headers=headers, json=payload, timeout=15)
        response.raise_for_status()
        data = response.json()
        if data.get('code') == 0 and data.get('success') and data.get('data', {}).get('data'):
            return data['data']['data'][0]
        else:
            print(f"获取详情失败: {data.get('message', '未知错误')}")
            return {}
    except Exception as e:
        print(f"获取下载链接异常: {e}")
        return {}


def sanitize_filename(filename: str) -> str:
    """去除文件名中的非法字符（Windows/Linux通用）"""
    return re.sub(r'[\\/*?:"<>|]', "", filename)


def download_file(url: str, save_path: str):
    """下载文件并显示进度条，添加防盗链头"""
    # 关键修改：添加 Referer 和 User-Agent 头以绕过防盗链
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36',
        'Referer': 'http://www.kuwo.cn/',
    }
    try:
        with requests.get(url, headers=headers, stream=True, timeout=30) as r:
            r.raise_for_status()
            total_size = int(r.headers.get('content-length', 0))
            downloaded = 0
            chunk_size = 8192
            with open(save_path, 'wb') as f:
                for chunk in r.iter_content(chunk_size=chunk_size):
                    if chunk:
                        f.write(chunk)
                        downloaded += len(chunk)
                        if total_size:
                            percent = downloaded / total_size * 100
                            print(f"\r下载进度: {percent:.1f}% ({downloaded}/{total_size} bytes)", end='')
            print()  # 换行
        return True
    except Exception as e:
        print(f"\n下载失败: {e}")
        return False


if __name__ == '__main__':
    keyword = input("请输入搜索关键词：").strip()
    if not keyword:
        print("关键词不能为空！")
        exit(0)

    songs = get_music_list(keyword)
    if not songs:
        exit(0)

    # 创建下载目录
    os.makedirs(DOWNLOAD_DIR, exist_ok=True)

    while True:
        try:
            choice = input("\n请输入要下载的歌曲序号 (输入 q 退出): ").strip()
            if choice.lower() == 'q':
                break

            idx = int(choice)
            selected = next((s for s in songs if s['index'] == idx), None)
            if not selected:
                print("序号无效，请重新输入")
                continue

            song_id = selected['id']
            print(f"已选择: {selected['name']} - {selected['artists']}")

            quality_choose = input("请选择音质、\n1.128k\n2.320k\n3.flac\n4.flac24bit): ").strip()
            if quality_choose == '1':
                quality = '128k'
            elif quality_choose == '2':
                quality = '320k'
            elif quality_choose == '3':
                quality = 'flac'
            elif quality_choose == '4':
                quality = 'flac24bit'
            else:
                print("音质选项错误，请重新输入")
                continue

            print("正在获取歌曲信息...")
            detail = get_song_detail(song_id, quality)
            if not detail:
                continue

            # 提取必要信息
            download_url = detail.get('url')
            if not download_url:
                print("未获取到下载链接")
                continue

            info = detail.get('info', {})
            song_name = info.get('name', '未知歌曲')
            artist = info.get('artist', '未知艺术家')
            cover_url = detail.get('cover')
            lyrics = detail.get('lyrics')

            # 构建文件名：歌曲名-艺术家.扩展名
            base_name = f"{song_name}-{artist}"
            base_name = sanitize_filename(base_name)

            # 从URL提取扩展名
            ext = os.path.splitext(download_url.split('?')[0])[1]
            if not ext:
                ext = '.mp3'  # 默认扩展名
            filename = base_name + ext
            save_path = os.path.join(DOWNLOAD_DIR, filename)

            print(f"准备下载: {filename}")
            print(f"封面地址: {cover_url}")
            print(f"歌词预览: {lyrics[:]}..." if lyrics else "无歌词")

            confirm = input("确认下载？(y/n): ").strip().lower()
            if confirm != 'y':
                print("已取消下载")
                continue

            success = download_file(download_url, save_path)
            if success:
                print(f"下载完成，保存至: {save_path}")
            else:
                print("下载失败")

        except ValueError:
            print("请输入有效的数字序号")
        except KeyboardInterrupt:
            print("\n用户中断")
            break
        except Exception as e:
            print(f"发生未知错误: {e}")

    print("程序结束。")